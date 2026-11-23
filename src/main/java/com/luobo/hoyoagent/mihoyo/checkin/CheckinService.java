package com.luobo.hoyoagent.mihoyo.checkin;

import com.luobo.hoyoagent.common.exception.BusinessException;
import com.luobo.hoyoagent.common.exception.ErrorCode;
import com.luobo.hoyoagent.mihoyo.checkin.dto.LunaInfo;
import com.luobo.hoyoagent.mihoyo.credential.Credential;
import com.luobo.hoyoagent.mihoyo.role.GameRole;
import com.luobo.hoyoagent.mihoyo.error.ErrorKind;
import com.luobo.hoyoagent.mihoyo.error.MihoyoError;
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
 * 定时调度与"今天跑过没有"的记录在 {@link CheckinScheduler} / {@link CheckinRecordStore}，
 * 本类不碰文件系统，也不决定"要不要签"。
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

    public CheckinRunResult checkinAll(Credential credential) {
        return checkinAll(credential, null);
    }

    /**
     * @param only {@code null} 表示当前账号下所有支持的游戏；否则只签这一个游戏。
     *             调度器永远传 {@code null}，只有工具层会指定单个游戏。
     */
    public CheckinRunResult checkinAll(Credential credential, CheckinGame only) {
        List<GameRole> roles;
        try {
            roles = roleService.roles(credential);
        } catch (BusinessException e) {
            return new CheckinRunResult(List.of(), "读取绑定角色失败：" + e.getMessage(), kindOf(e));
        }

        List<GameRole> supported = roles.stream()
                .filter(r -> CheckinGame.of(r.gameBiz()).isPresent())
                .filter(r -> only == null || only.gameBiz().equals(r.gameBiz()))
                .toList();
        if (supported.isEmpty()) {
            return new CheckinRunResult(List.of(), only == null
                    ? "当前账号没有可签到的角色（原神/星铁/绝区零）"
                    : "当前账号没有绑定" + only.displayName() + "的角色");
        }

        List<CheckinRoleResult> results = new ArrayList<>();
        for (int i = 0; i < supported.size(); i++) {
            results.add(checkinGame(credential, supported.get(i)));
            if (i < supported.size() - 1) {
                pause();
            }
        }
        return new CheckinRunResult(results, summarize(results));
    }

    /**
     * 签一个游戏。给批量流程、单游戏调用和测试共用。
     *
     * <p>任何运行时异常都收敛成一条 {@code FAILED} 结果：一个游戏的数据解析炸了，
     * 不该把另外两个游戏的结论一起吞掉（调度器只会在账号层面记一笔「签到异常」）。
     */
    public CheckinRoleResult checkinGame(Credential credential, GameRole role) {
        CheckinGame game = CheckinGame.of(role.gameBiz())
                .orElseThrow(() -> new IllegalArgumentException("不支持的游戏: " + role.gameBiz()));
        try {
            return attemptCheckin(credential, game, role);
        } catch (RuntimeException e) {
            log.warn("签到异常: {} uid={}", game.displayName(), role.gameUid(), e);
            ErrorKind kind = e instanceof BusinessException be ? kindOf(be) : ErrorKind.UNKNOWN;
            return new CheckinRoleResult(game.gameBiz(), game.displayName(), role.gameUid(), role.region(),
                    "FAILED", "签到异常：" + detailOf(e), 0, "", kind);
        }
    }

    private static String detailOf(RuntimeException e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    private CheckinRoleResult attemptCheckin(Credential credential, CheckinGame game, GameRole role) {
        MihoyoResult result = luna.info(credential, game, role.region(), role.gameUid());
        if (!result.ok()) {
            return fail(game, role, result.error(), 0);
        }
        LunaInfo info = mapper.treeToValue(result.data(), LunaInfo.class);

        if (info.isFirstBind()) {
            return new CheckinRoleResult(game.gameBiz(), game.displayName(), role.gameUid(), role.region(),
                    "FIRST_BIND", "还没在米游社绑定这个角色，先去 App 里绑定再签", 0, "", null);
        }

        int days = info.totalSignDay();
        if (info.isSign()) {
            return new CheckinRoleResult(game.gameBiz(), game.displayName(), role.gameUid(), role.region(),
                    "ALREADY", "今天已经签过了", days, reward(credential, game, days), null);
        }

        MihoyoResult sign = luna.sign(credential, game, role.region(), role.gameUid());
        if (sign.retcode() != 0) {
            return fail(game, role, sign.error(), days);
        }
        if (sign.data().path("success").asInt(0) != 0) {
            return new CheckinRoleResult(game.gameBiz(), game.displayName(), role.gameUid(), role.region(),
                    "CAPTCHA", "签到触发了人机验证，需要去米游社 App 手动签一次", days, "",
                    ErrorKind.NEED_VERIFY);
        }

        // 复验：sign 返回 0 不等于真的签上了
        MihoyoResult after = luna.info(credential, game, role.region(), role.gameUid());
        if (!after.ok() || !after.data().path("is_sign").asBoolean(false)) {
            return new CheckinRoleResult(game.gameBiz(), game.displayName(), role.gameUid(), role.region(),
                    "FAILED", "签到请求返回成功，但复验未通过", days, "", ErrorKind.UNKNOWN);
        }

        int afterDays = after.data().path("total_sign_day").asInt(days);
        log.info("签到成功: {} uid={} 累计 {} 天", game.displayName(), role.gameUid(), afterDays);
        return new CheckinRoleResult(game.gameBiz(), game.displayName(), role.gameUid(), role.region(),
                "SIGNED", "签到成功", afterDays, reward(credential, game, afterDays), null);
    }

    /** 奖励名（「原石」x60 这种）；拿不到就返回空串，不影响签到结论。 */
    private String reward(Credential credential, CheckinGame game, int days) {
        try {
            MihoyoResult home = luna.home(credential, game);
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

    private static CheckinRoleResult fail(CheckinGame game, GameRole role, MihoyoError error, int days) {
        return new CheckinRoleResult(game.gameBiz(), game.displayName(), role.gameUid(), role.region(),
                "FAILED", error.userMessage(), days, "", error.kind());
    }

    /** 把 {@link BusinessException} 的 9xxx 码翻译回类别。读角色这一步没有 MihoyoResult 可看。 */
    private static ErrorKind kindOf(BusinessException e) {
        Integer code = e.getCode();
        if (ErrorCode.MOYO_AUTH_EXPIRED.getCode().equals(code)) {
            return ErrorKind.AUTH;
        }
        if (ErrorCode.MOYO_NEED_VERIFY.getCode().equals(code)) {
            return ErrorKind.NEED_VERIFY;
        }
        if (ErrorCode.MOYO_RATE_LIMITED.getCode().equals(code)) {
            return ErrorKind.RATE_LIMIT;
        }
        if (ErrorCode.MOYO_SIGN_INVALID.getCode().equals(code)) {
            return ErrorKind.SIGN;
        }
        return ErrorKind.UNKNOWN;
    }

    private static String summarize(List<CheckinRoleResult> results) {
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
}
