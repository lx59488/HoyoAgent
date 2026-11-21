package com.luobo.hoyoagent.mihoyo.checkin;

import com.luobo.hoyoagent.mihoyo.transport.MihoyoHost;

import java.util.Arrays;
import java.util.Optional;

/**
 * 各游戏的签到活动参数。
 *
 * <p>{@code act_id} 是**活动常量**，不是"每期变"——FufuLauncher 用了两年多没改过
 * （原神 {@code e202311201442471}）。之前文档里"每期变、要从活动信息接口取"的说法是错的。
 *
 * <p>绝区零的 host 和路径都不同（{@link MihoyoHost#NAP} + {@code /event/luna/zzz}），
 * 不能简单把 act_id 换掉就用。
 *
 * <p>2026-11-20：host 不再写成字面量，而是 {@link MihoyoHost} 枚举 + {@code MihoyoEndpoints} 解析。
 * 之前这里硬编码 {@code https://api-takumi.mihoyo.com}，导致 {@code hoyo.hosts} 只覆盖了
 * 战绩/passport 两条路由，签到会偷偷绕过配置打真网。
 */
public enum CheckinGame {

    GENSHIN("hk4e_cn", "原神", "e202311201442471",
            MihoyoHost.TAKUMI, "/event/luna", "https://act.mihoyo.com/", "hk4e"),

    STAR_RAIL("hkrpg_cn", "崩坏：星穹铁道", "e202304121516551",
            MihoyoHost.TAKUMI, "/event/luna", "https://act.mihoyo.com/", null),

    ZZZ("nap_cn", "绝区零", "e202406242138391",
            MihoyoHost.NAP, "/event/luna/zzz", "https://act.mihoyo.com/", "zzz");

    private final String gameBiz;

    private final String displayName;

    private final String actId;

    private final MihoyoHost host;

    private final String lunaPath;

    private final String referer;

    private final String signGame;

    CheckinGame(String gameBiz, String displayName, String actId,
                MihoyoHost host, String lunaPath, String referer, String signGame) {
        this.gameBiz = gameBiz;
        this.displayName = displayName;
        this.actId = actId;
        this.host = host;
        this.lunaPath = lunaPath;
        this.referer = referer;
        this.signGame = signGame;
    }

    public String gameBiz() {
        return gameBiz;
    }

    public String displayName() {
        return displayName;
    }

    public String actId() {
        return actId;
    }

    public MihoyoHost host() {
        return host;
    }

    /** luna 接口的路径前缀，拼在 host 之后、action 之前。 */
    public String path() {
        return lunaPath;
    }

    public String referer() {
        return referer;
    }

    public String signGame() {
        return signGame;
    }

    public static Optional<CheckinGame> of(String gameBiz) {
        return Arrays.stream(values()).filter(g -> g.gameBiz.equals(gameBiz)).findFirst();
    }
}
