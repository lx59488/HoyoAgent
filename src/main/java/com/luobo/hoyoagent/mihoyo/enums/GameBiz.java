package com.luobo.hoyoagent.mihoyo.enums;

/** 游戏业务标识（game_biz），接口参数里到处用，集中一处避免拼写漂移。 */
public enum GameBiz {

    GENSHIN("hk4e_cn"),
    STAR_RAIL("hkrpg_cn"),
    ZZZ("nap_cn");

    private final String value;

    GameBiz(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
