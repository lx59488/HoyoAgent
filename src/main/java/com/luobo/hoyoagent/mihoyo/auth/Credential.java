package com.luobo.hoyoagent.mihoyo.auth;

/**
 * 一个米游社账号的凭据。{@code rawToken} 是可直接放进 Cookie 头的原始串。
 *
 * <p>{@code stoken} 单独拎出来，是因为签到类接口只认 {@code stuid+stoken+mid} 这组 cookie，
 * 光有 ltoken 会稳定返回 {@code -100}（2026-11-12 复测）。
 */
public record Credential(String rawToken, String uid, String mid, String stoken) {

    public Credential(String rawToken, String uid, String mid) {
        this(rawToken, uid, mid, null);
    }

    public String cookieHeader() {
        return rawToken == null ? "" : rawToken;
    }

    public boolean hasStoken() {
        return stoken != null && !stoken.isBlank();
    }

    /** 只用于日志/接口回显。 */
    public String midMasked() {
        if (mid == null || mid.isBlank()) {
            return null;
        }
        return mid.length() <= 4 ? "***" : "***" + mid.substring(mid.length() - 4);
    }

    @Override
    public String toString() {
        return "Credential[uid=" + uid + ", mid=" + midMasked() + ", stoken=" + (hasStoken() ? "present" : "absent")
                + ", cookie=***]";
    }
}
