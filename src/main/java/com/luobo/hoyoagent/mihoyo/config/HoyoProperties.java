package com.luobo.hoyoagent.mihoyo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

@ConfigurationProperties(prefix = "hoyo")
public record HoyoProperties(
        Map<String, String> hosts,
        String appVersion,
        String clientType,
        String userAgent,
        String referer,
        Duration timeout,
        String deviceId
) {
}
