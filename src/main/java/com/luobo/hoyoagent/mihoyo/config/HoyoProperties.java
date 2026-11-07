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
        String salt,
        Duration timeout,
        String deviceId,
        String roleId,
        String server
) {
}
