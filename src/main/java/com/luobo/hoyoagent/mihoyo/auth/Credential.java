package com.luobo.hoyoagent.mihoyo.auth;

public record Credential(String rawToken, String uid, String mid) {

    public String cookieHeader() {
        return rawToken;
    }


    @Override
    public String toString() {
        return "Credential[uid=" + uid + ", mid=" + (mid == null ? null : "***") + ", cookie=***]";
    }
}
