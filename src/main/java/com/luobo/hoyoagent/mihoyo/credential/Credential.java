package com.luobo.hoyoagent.mihoyo.credential;

import com.luobo.hoyoagent.common.log.Mask;

/**
 * 一个米游社账号的凭据。
 *
 * <p>{@code cookies} 是完整 cookie 的不可变表示（见 {@link CookieJar}）；{@code stoken} 单独拎出来，
 * 是因为换 v2 / 社区操作要显式用它。**注意**：游戏签到并不需要 stoken，它认的是
 * {@code cookie_token} + {@code account_id}（11-12 对照实验，见 docs/mihoyo-auth.md §2）。
 *
 * <p>日志安全：{@link #toString()} 只暴露 uid、掩码后的 mid 和"有没有 stoken"，绝不打印凭据值。
 */
public record Credential(CookieJar cookies, String uid, String mid, String stoken) {

    /** 解析 cookie 头的唯一入口（uid/mid/stoken 的回退顺序也在这里定义）。 */
    public static Credential of(String cookieHeader) {
        CookieJar jar = CookieJar.of(cookieHeader);
        return new Credential(jar,
                firstPresent(jar, "ltuid_v2", "ltuid", "account_id_v2", "account_id", "stuid"),
                firstPresent(jar, "ltmid_v2", "mid", "account_mid_v2"),
                firstPresent(jar, "stoken", "stoken_v2"));
    }

    public static Credential empty() {
        return new Credential(CookieJar.empty(), null, null, null);
    }

    public String cookieHeader() {
        return cookies.header();
    }

    /** 有没有能真正发出去的 cookie；"空凭据"和"有 cookie 但没 token"是两种状态，调用方别自己判空串。 */
    public boolean hasCookie() {
        return cookies != null && !cookies.header().isBlank();
    }

    public boolean hasStoken() {
        return stoken != null && !stoken.isBlank();
    }

    public boolean has(String cookieName) {
        return cookies.has(cookieName);
    }

    /** 给接口/日志回显用，只保留后 4 位；mid 缺失时返回 null，让前端显示"—"而不是"***"。 */
    public String midMasked() {
        return maskMid(mid);
    }

    public Credential withCookies(CookieJar fresh) {
        return new Credential(fresh, uid, mid, stoken);
    }

    @Override
    public String toString() {
        return "Credential[uid=" + uid
                + ", mid=" + maskMid(mid)
                + ", stoken=" + (hasStoken() ? "present" : "absent")
                + ", cookies=" + (cookieHeader().isBlank() ? "none" : "***") + "]";
    }

    private static String firstPresent(CookieJar jar, String... names) {
        for (String name : names) {
            String value = jar.get(name).orElse(null);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String maskMid(String value) {
        return value == null || value.isBlank() ? null : Mask.tail(value, 4);
    }
}
