package com.luobo.hoyoagent.agent.transcript;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 聊天历史的配置。默认值写在这里，yml 只负责暴露环境变量。
 *
 * @param enabled   总开关；关掉后不建目录、不写、列表为空
 * @param dir       落盘目录；空 = {@code <state-dir>/transcripts}
 * @param retention 保留期；默认 90 天（比记忆窗口的 30 天长，历史本来就更值得留）
 */
@ConfigurationProperties("hoyo.transcript")
public record TranscriptProperties(Boolean enabled, String dir, Duration retention) {

    public TranscriptProperties {
        enabled = enabled == null || enabled;
        retention = retention == null || retention.isZero() || retention.isNegative()
                ? Duration.ofDays(90)
                : retention;
        dir = dir == null ? "" : dir.trim();
    }

    public boolean recording() {
        return enabled;
    }
}
