package com.luobo.hoyoagent.mihoyo.checkin;

import com.luobo.hoyoagent.common.exception.BusinessException;
import com.luobo.hoyoagent.mihoyo.checkin.dto.LunaInfo;
import com.luobo.hoyoagent.mihoyo.credential.AccountService;
import com.luobo.hoyoagent.mihoyo.credential.Credential;
import com.luobo.hoyoagent.mihoyo.credential.CredentialProvider;
import com.luobo.hoyoagent.mihoyo.dto.GameRole;
import com.luobo.hoyoagent.mihoyo.role.GameRoleService;
import com.luobo.hoyoagent.mihoyo.transport.MihoyoResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
@Service
public class CheckinOverviewService {

    private static final Logger log = LoggerFactory.getLogger(CheckinOverviewService.class);

    /** 游戏之间停一下，别让三个请求挤在同一毫秒 */
    private static final long BETWEEN_GAMES_MS = 300;

    private final GameRoleService roleService;

    private final LunaClient luna;

    private final CheckinRecordStore records;

    private final AccountService accounts;

    private final ObjectMapper mapper;

    private final ZoneId zone;

    public CheckinOverviewService(GameRoleService roleService,
                                  LunaClient luna,
                                  CheckinRecordStore records,
                                  AccountService accounts,
                                  ObjectMapper mapper,
                                  @Value("${hoyo.checkin.zone:Asia/Shanghai}") String zone) {
        this.roleService = roleService;
        this.luna = luna;
        this.records = records;
        this.accounts = accounts;
        this.mapper = mapper;
        this.zone = ZoneId.of(zone);
    }

    /** 临时桥：M1-c 之后由工具直接传 userId + 凭据。 */
    public CheckinOverview overview() {
        String userId = accounts.localUserId();
        return overview(userId, accounts.find(userId).orElse(null));
    }


    public CheckinOverview overview(String userId, Credential credential) {
        LocalDate today = LocalDate.now(zone);
        String todayText = today.toString();

        if (credential == null || credential.cookieHeader() == null || credential.cookieHeader().isBlank()) {
            return new CheckinOverview("还没绑定米游社账号，回复「绑定」获取二维码",
                    List.of(), recent(userId), todayText);
        }

        List<GameRole> roles;
        try {
            roles = roleService.roles(credential);
        } catch (BusinessException e) {
            return new CheckinOverview("读取绑定角色失败：" + e.getMessage(),
                    List.of(), recent(userId), todayText);
        }

        List<GameRole> supported = roles.stream()
                .filter(r -> CheckinGame.of(r.gameBiz()).isPresent())
                .toList();
        if (supported.isEmpty()) {
            return new CheckinOverview("当前账号没有可签到的角色（原神/星铁/绝区零）",
                    List.of(), recent(userId), todayText);
        }

        List<CheckinOverview.Game> games = new ArrayList<>();
        for (int i = 0; i < supported.size(); i++) {
            games.add(readOne(credential, supported.get(i), today));
            if (i < supported.size() - 1) {
                pause();
            }
        }
        return new CheckinOverview(summarize(games), games, recent(userId), todayText);
    }

    /** 一个游戏失败不能拖垮整张总览：失败就写进那一行，其余照常返回。 */
    private CheckinOverview.Game readOne(Credential credential, GameRole role, LocalDate today) {
        CheckinGame game = CheckinGame.of(role.gameBiz()).orElseThrow();
        String gameName = game.displayName();
        String uid = role.gameUid();

        MihoyoResult result;
        try {
            result = luna.info(credential, game, role.region(), uid);
        } catch (RuntimeException e) {
            // MihoyoHttp 对 HTTP/IO 错误抛的是 BusinessException
            return new CheckinOverview.Game(role.gameBiz(), gameName, uid,
                    "FAILED", readable(e), 0, 0);
        }
        if (!result.ok()) {
            return new CheckinOverview.Game(role.gameBiz(), gameName, uid,
                    "FAILED", result.error().userMessage(), 0, 0);
        }

        LunaInfo info;
        try {
            info = mapper.treeToValue(result.data(), LunaInfo.class);
        } catch (RuntimeException e) {
            log.warn("luna/info 解析失败: {} uid={} err={}", gameName, uid, e.toString());
            return new CheckinOverview.Game(role.gameBiz(), gameName, uid,
                    "FAILED", "返回数据解析失败", 0, 0);
        }

        if (info.isFirstBind()) {
            return new CheckinOverview.Game(role.gameBiz(), gameName, uid,
                    "FIRST_BIND", "还没在米游社绑定这个角色，不能签", info.totalSignDay(), 0);
        }

        int days = info.totalSignDay();
        return new CheckinOverview.Game(role.gameBiz(), gameName, uid,
                info.isSign() ? "SIGNED" : "UNSIGNED",
                info.isSign() ? "今天已签" : "今天还没签",
                days,
                missedEstimate(days, today.getDayOfMonth()));
    }

    /**
     * 本月漏签天数（推算）：今天几号 - 本月累计签到天数。
     *
     * <p>前提是服务端 {@code total_sign_day} 是"本月累计"。如果哪天发现它是历史累计，
     * 这个数会恒为 0 —— 那时把这一行删掉，不要在文案里硬编。
     */
    static int missedEstimate(int totalDays, int dayOfMonth) {
        return Math.max(0, dayOfMonth - totalDays);
    }

    static String summarize(List<CheckinOverview.Game> games) {
        long signed = games.stream().filter(g -> "SIGNED".equals(g.status())).count();
        long unsigned = games.stream().filter(g -> "UNSIGNED".equals(g.status())).count();
        long attention = games.size() - signed - unsigned;
        StringBuilder sb = new StringBuilder();
        sb.append(games.size()).append(" 个角色：已签 ").append(signed).append("，未签 ").append(unsigned);
        if (attention > 0) {
            sb.append("，需处理 ").append(attention);
        }
        return sb.toString();
    }

    /** 本地记录只用于"最近 7 天"；按 userId 过滤，别把别人的记录当成自己的。 */
    private List<CheckinOverview.Recent> recent(String userId) {
        return records.recent(userId, 7).stream()
                .map(r -> new CheckinOverview.Recent(
                        r.date(),
                        r.trigger() == null ? "UNKNOWN" : r.trigger().name(),
                        r.outcome() == null ? "UNKNOWN" : r.outcome().name(),
                        r.message() == null ? "" : r.message()))
                .toList();
    }

    private static String readable(RuntimeException e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? "请求失败" : e.getMessage();
    }

    private static void pause() {
        try {
            Thread.sleep(BETWEEN_GAMES_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
