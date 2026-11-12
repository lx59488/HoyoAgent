package com.luobo.hoyoagent.mihoyo.dto;

/**
 * 账号下绑定的游戏角色（原神/星铁/绝区零各一条）。
 *
 * <p>来源：{@code GET /binding/api/getUserGameRolesByCookie}，只需要 ltoken，不需要 DS。
 * 有了它，工具就不用再依赖 {@code HOYO_ROLE_ID} 这种"写死某个号"的配置。
 */
public record GameRole(
        String gameBiz,   // hk4e_cn / hkrpg_cn / nap_cn
        String region,    // cn_gf01 / prod_gf_cn ...
        String gameUid,   // 游戏内 uid，如 100848866
        String nickname,
        int level
) {
}
