package com.luobo.hoyoagent.agent.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "hoyo.memory")
public record MemoryProperties (
        Integer maxMessages,
        Integer maxSessions,
        Duration idleTtl
) {

    private static final int FALLBACK_MAX_MESSAGES = 20;
    private static final int FALLBACK_MAX_SESSIONS = 200;
    private static final Duration FALLBACK_IDLE_TTL = Duration.ofHours(2);

    public MemoryProperties {
        // 窗口 < 2 等于只留一条 SystemMessage，直接按默认值兜底
        maxMessages = maxMessages == null || maxMessages < 2 ? FALLBACK_MAX_MESSAGES : maxMessages;
        maxSessions = maxSessions == null || maxSessions < 1 ? FALLBACK_MAX_SESSIONS : maxSessions;
        idleTtl = idleTtl == null || idleTtl.isZero() || idleTtl.isNegative()
                ? FALLBACK_IDLE_TTL
                : idleTtl;
    }
}
