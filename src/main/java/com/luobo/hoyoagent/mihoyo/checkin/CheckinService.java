package com.luobo.hoyoagent.mihoyo.checkin;

import com.luobo.hoyoagent.common.exception.BusinessException;
import com.luobo.hoyoagent.mihoyo.checkin.dto.LunaInfo;
import com.luobo.hoyoagent.mihoyo.dto.GameRole;
import com.luobo.hoyoagent.mihoyo.role.GameRoleService;
import com.luobo.hoyoagent.mihoyo.transport.MihoyoResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 米游社每日签到。
 *
 * <p>流程固定为 <b>info → 未签则 sign → info 复验</b>，每一步都把 retcode 翻译成人话：
 * 失败原因能区分"登录失效""没绑定角色""弹了验证码"，而不是笼统报错。
 *
 * <p>幂等靠 {@code is_sign}：同一天重复调用只会返回"今天已经签过"，不会重复签。
 * 跨天调度的持久化记录（{@code checkin_records}）留到多用户阶段。
 */
@Service
public class CheckinService {

    private static final Logger log = LoggerFactory.getLogger(CheckinService.class);

    /** 角色之间稍作停顿，别让三个游戏的请求挤在同一毫秒 */
    private static final long BETWEEN_ROLES_MS = 800;

    private final GameRoleService roleService;

    private final LunaClient luna;

    private final ObjectMapper mapper;

    public CheckinService(GameRoleService roleService, LunaClient luna, ObjectMapper mapper) {
        this.roleService = roleService;
        this.luna = luna;
        this.mapper = mapper;
    }

    public Summary checkinAll() {
        List<GameRole> roles;
        try {
            roles = roleService.roles();
        } catch (BusinessException e) {
            return new Summary(List.of(), "读取绑定角色失败：" + e.getMessage());
        }

        List<GameRole> supported = roles.stream()
                .filter(r -> CheckinGame.of(r.gameBiz()).isPresent())
                .toList();
        if (supported.isEmpty()) {
            return new Summary(List.of(), "当前账号没有可签到的角色（原神/星铁/绝区零）");
        }

        List<RoleResult> results = new ArrayList<>();
        for (int i = 0; i < supported.size(); i++) {
            GameRole role = supported.get(i);
            CheckinGame game = CheckinGame.of(role.gameBiz()).orElseThrow();
            results.add(checkinOne(game, role));
            if (i < supported.size() - 1) {
                pause();
            }
        }
        return new Summary(results, summarize(results));
    }

    private RoleResult checkinOne(CheckinGame game, GameRole role) {
        MihoyoResult result = luna.info(game, role.region(), role.gameUid());
        if (!result.ok()) {
            return fail(game, role, result.error().userMessage(), 0);
        }
        LunaInfo info = mapper.treeToValue(result.data(), LunaInfo.class);

        if (info.isFirstBind()) {
            return new RoleResult(game.gameBiz(), game.displayName(), role.gameUid(), role.region(),
                    "FIRST_BIND", "还没在米游社绑定这个角色，先去 App 里绑定再签", 0, "");
        }

        int days = info.totalSignDay();
        if (info.isSign()) {
            return new RoleResult(game.gameBiz(), game.displayName(), role.gameUid(), role.region(),
                    "ALREADY", "今天已经签过了", days, reward(game, days));
        }

        MihoyoResult sign = luna.sign(game, role.region(), role.gameUid());
        if (sign.retcode() != 0) {
            return fail(game, role, translate(sign.retcode()), days);
        }
        if (sign.data().path("success").asInt(0) != 0) {
            return new RoleResult(game.gameBiz(), game.displayName(), role.gameUid(), role.region(),
                    "CAPTCHA", "签到触发了人机验证，需要去米游社 App 手动签一次", days, "");
        }

        // 复验：sign 返回 0 不等于真的签上了
        MihoyoResult after = luna.info(game, role.region(), role.gameUid());
        if (!after.ok() || !after.data().path("is_sign").asBoolean(false)) {
            return new RoleResult(game.gameBiz(), game.displayName(), role.gameUid(), role.region(),
                    "FAILED", "签到请求返回成功，但复验未通过", days, "");
        }

        int afterDays = after.data().path("total_sign_day").asInt(days);
        log.info("签到成功: {} uid={} 累计 {} 天", game.displayName(), role.gameUid(), afterDays);
        return new RoleResult(game.gameBiz(), game.displayName(), role.gameUid(), role.region(),
                "SIGNED", "签到成功", afterDays, reward(game, afterDays));
    }

    /** 奖励名（「原石」x60 这种）；拿不到就返回空串，不影响签到结论。 */
    private String reward(CheckinGame game, int days) {
        try {
            MihoyoResult home = luna.home(game);
            if (!home.ok()) {
                return "";
            }
            var awards = home.data().path("awards");
            if (!awards.isArray() || days <= 0 || days > awards.size()) {
                return "";
            }
            var item = awards.get(days - 1);
            String name = item.path("name").asString("");
            int count = item.path("cnt").asInt(0);
            return name.isBlank() ? "" : "「" + name + "」x" + count;
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String translate(int retcode) {
        return switch (retcode) {
            case -100, 10001 -> "登录状态失效（-100），请重新扫码绑定";
            case -500004 -> "请求太频繁（-500004），过一会儿再试";
            case 1034 -> "需要人机验证（1034），去 App 手动签一次";
            default -> "米游社返回异常 retcode=" + retcode;
        };
    }

    private static RoleResult fail(CheckinGame game, GameRole role, String message, int days) {
        return new RoleResult(game.gameBiz(), game.displayName(), role.gameUid(), role.region(),
                "FAILED", message, days, "");
    }

    private static String summarize(List<RoleResult> results) {
        long signed = results.stream().filter(r -> "SIGNED".equals(r.status())).count();
        long already = results.stream().filter(r -> "ALREADY".equals(r.status())).count();
        long failed = results.size() - signed - already;
        StringBuilder sb = new StringBuilder();
        sb.append(results.size()).append(" 个角色：");
        sb.append("新签 ").append(signed);
        if (already > 0) {
            sb.append("，已签 ").append(already);
        }
        if (failed > 0) {
            sb.append("，失败/需处理 ").append(failed);
        }
        return sb.toString();
    }

    private static void pause() {
        try {
            Thread.sleep(BETWEEN_ROLES_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** status: SIGNED（今天新签）/ ALREADY / FIRST_BIND / CAPTCHA / FAILED */
    public record RoleResult(String gameBiz,
                             String gameName,
                             String uid,
                             String region,
                             String status,
                             String message,
                             int totalDays,
                             String reward) {
    }

    public record Summary(List<RoleResult> results, String message) {
    }
}
