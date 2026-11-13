package com.luobo.hoyoagent.agent.memory;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置兜底的三个分支。这不是形式主义：`.env` 里不写 hoyo.memory.* 时，
 * 走的就是"全 null"这条路径，而它不能变成 0 —— maxSessions=0 会把刚写的会话立刻淘汰。
 */
class MemoryPropertiesTest {

    @Test
    void missingConfigFallsBackToDefaults() {
        MemoryProperties properties = new MemoryProperties(null, null, null);

        assertThat(properties.maxMessages()).isEqualTo(20);
        assertThat(properties.maxSessions()).isEqualTo(200);
        assertThat(properties.idleTtl()).isEqualTo(Duration.ofHours(2));
    }

    @Test
    void nonsenseValuesFallBackInsteadOfBreakingTheWindow() {
        // 窗口 1 等于只留 SystemMessage，等于记住"没有上下文"
        MemoryProperties properties = new MemoryProperties(1, 0, Duration.ZERO);

        assertThat(properties.maxMessages()).isEqualTo(20);
        assertThat(properties.maxSessions()).isEqualTo(200);
        assertThat(properties.idleTtl()).isEqualTo(Duration.ofHours(2));
    }

    @Test
    void explicitValuesAreKept() {
        MemoryProperties properties = new MemoryProperties(10, 5, Duration.ofMinutes(30));

        assertThat(properties.maxMessages()).isEqualTo(10);
        assertThat(properties.maxSessions()).isEqualTo(5);
        assertThat(properties.idleTtl()).isEqualTo(Duration.ofMinutes(30));
    }
}