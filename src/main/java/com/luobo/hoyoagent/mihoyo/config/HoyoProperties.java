package com.luobo.hoyoagent.mihoyo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "hoyo")
public record HoyoProperties(
        String baseUrl,
        String appVersion,
        String clientType,
        String userAgent,
        String referer,
        String salt,
        Duration timeout
) {
}
