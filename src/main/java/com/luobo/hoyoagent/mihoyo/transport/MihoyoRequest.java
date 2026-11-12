package com.luobo.hoyoagent.mihoyo.transport;

import java.util.Map;

/**
 * 不可变请求。query 单独放，由 transport 负责编码，避免每处手拼 {@code ?a=b}。
 *
 * <p>工厂方法把 {@code query}/{@code headers} 的空值统一成 {@link Map#of()}：
 * 传输层不应该因为调用方少传一个参数就抛 NPE。
 */
public record MihoyoRequest(String method,
                            String url,
                            Map<String, String> query,
                            Map<String, String> headers,
                            String body) {

    public MihoyoRequest {
        query = query == null ? Map.of() : Map.copyOf(query);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    public static MihoyoRequest get(String url, Map<String, String> query, Map<String, String> headers) {
        return new MihoyoRequest("GET", url, query, headers, null);
    }

    public static MihoyoRequest post(String url, String body, Map<String, String> headers) {
        return new MihoyoRequest("POST", url, null, headers, body);
    }
}
