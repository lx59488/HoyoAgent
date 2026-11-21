package com.luobo.hoyoagent.mihoyo.transport;

/**
 * 米游社各个 host 的默认值。
 *
 * <p>默认值放这里，覆盖入口只有 {@code hoyo.hosts}（见 {@code MihoyoEndpoints}）；
 * 调用方一律通过 {@code MihoyoEndpoints#baseUrl} 取，不直接拼字符串——否则配置覆盖只对一半接口生效。
 */
public enum MihoyoHost {

    BBS("https://bbs-api.miyoushe.com"),
    RECORD("https://api-takumi-record.mihoyo.com"),
    TAKUMI("https://api-takumi.mihoyo.com"),
    PASSPORT("https://passport-api.mihoyo.com"),

    /** 绝区零签到走的独立 host（{@code act-nap-api}），不能拿 TAKUMI 换路径凑 */
    NAP("https://act-nap-api.mihoyo.com");

    private final String defaultUrl;

    MihoyoHost(String defaultUrl) {
        this.defaultUrl = defaultUrl;
    }

    public String defaultUrl() {
        return defaultUrl;
    }
}
