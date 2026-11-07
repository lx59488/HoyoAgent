package com.luobo.hoyoagent.mihoyo.enums;

public enum MiyousheHost {
    BBS("https://bbs-api.miyoushe.com"),
    RECORD("https://api-takumi-record.mihoyo.com"),
    TAKUMI("https://api-takumi.mihoyo.com");

    private final String defaultUrl;

    MiyousheHost(String defaultUrl) {
        this.defaultUrl = defaultUrl;
    }

    public String defaultUrl() {
        return defaultUrl;
    }
}