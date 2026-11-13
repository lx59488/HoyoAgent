package com.luobo.hoyoagent.mihoyo.credential;

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

    public boolean hasStoken() {
        return stoken != null && !stoken.isBlank();
    }

    public boolean has(String cookieName) {
        return cookies.has(cookieName);
    }

    public Credential withCookies(CookieJar fresh) {
        return new Credential(fresh, uid, mid, stoken);
    }

    @Override
    public String toString() {
        return "Credential[uid=" + uid
                + ", mid=" + mask(mid)
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

    private static String mask(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() <= 4 ? "***" : "***" + value.substring(value.length() - 4);
    }
}
