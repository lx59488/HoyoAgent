package com.luobo.hoyoagent.channel.notify;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "hoyo.notify")
public record NotifyProperties(boolean enabled, String url, String kind, Duration timeout) {

    public NotifyProperties {
        url = url == null ? "" : url.trim();
        kind = (kind == null || kind.isBlank()) ? "generic" : kind.trim().toLowerCase();
        timeout = (timeout == null || timeout.isZero() || timeout.isNegative())
                ? Duration.ofSeconds(5) : timeout;
    }
}