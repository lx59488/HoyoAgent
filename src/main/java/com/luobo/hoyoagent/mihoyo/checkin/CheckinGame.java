package com.luobo.hoyoagent.mihoyo.checkin;

import java.util.Arrays;
import java.util.Optional;

/**
 * 各游戏的签到活动参数。
 *
 * <p>{@code act_id} 是**活动常量**，不是"每期变"——FufuLauncher 用了两年多没改过
 * （原神 {@code e202311201442471}）。之前文档里"每期变、要从活动信息接口取"的说法是错的。
 *
 * <p>绝区零的 host 和路径都不同（{@code act-nap-api} + {@code /event/luna/zzz}），
 * 不能简单把 act_id 换掉就用。
 */
public enum CheckinGame {

    GENSHIN("hk4e_cn", "原神", "e202311201442471",
            "https://api-takumi.mihoyo.com", "/event/luna", "https://act.mihoyo.com/", "hk4e"),

    STAR_RAIL("hkrpg_cn", "崩坏：星穹铁道", "e202304121516551",
            "https://api-takumi.mihoyo.com", "/event/luna", "https://act.mihoyo.com/", null),

    ZZZ("nap_cn", "绝区零", "e202406242138391",
            "https://act-nap-api.mihoyo.com", "/event/luna/zzz", "https://act.mihoyo.com/", "zzz");

    private final String gameBiz;

    private final String displayName;

    private final String actId;

    private final String baseUrl;

    private final String lunaPath;

    private final String referer;

    private final String signGame;

    CheckinGame(String gameBiz, String displayName, String actId,
                String baseUrl, String lunaPath, String referer, String signGame) {
        this.gameBiz = gameBiz;
        this.displayName = displayName;
        this.actId = actId;
        this.baseUrl = baseUrl;
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

    public String referer() {
        return referer;
    }

    public String signGame() {
        return signGame;
    }

    public String url(String action) {
        return baseUrl + lunaPath + action;
    }

    public static Optional<CheckinGame> of(String gameBiz) {
        return Arrays.stream(values()).filter(g -> g.gameBiz.equals(gameBiz)).findFirst();
    }
}
