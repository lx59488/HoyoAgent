package com.luobo.hoyoagent.agent.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "hoyo.memory")
public record MemoryProperties (
        Integer maxMessages,
        Integer maxSessions,
        Duration idleTtl,
        Compaction compaction
) {

    /**
     * 硬上限，不是模型每轮看到的窗口：超过 60 条才会被按轮裁掉。
     * 正常路径上先由 {@link ConversationCompactor} 压缩到「摘要 + 最近几轮」，远到不了这里。
     */
    private static final int FALLBACK_MAX_MESSAGES = 60;
    private static final int FALLBACK_MAX_SESSIONS = 200;
    private static final Duration FALLBACK_IDLE_TTL = Duration.ofHours(2);
    private static final int FALLBACK_KEEP_TURNS = 3;
    private static final int FALLBACK_SUMMARY_CHARS = 800;

    public MemoryProperties {
        // 窗口 < 2 等于只留一条 SystemMessage，直接按默认值兜底
        maxMessages = maxMessages == null || maxMessages < 2 ? FALLBACK_MAX_MESSAGES : maxMessages;
        maxSessions = maxSessions == null || maxSessions < 1 ? FALLBACK_MAX_SESSIONS : maxSessions;
        idleTtl = idleTtl == null || idleTtl.isZero() || idleTtl.isNegative()
                ? FALLBACK_IDLE_TTL
                : idleTtl;
        compaction = resolveCompaction(compaction, maxMessages);
    }

    // 这里只能保留 canonical 构造器。再加一个"便捷构造器"会让 Spring Boot
    // 放弃构造器绑定，转去找无参构造器，启动直接报 NoSuchMethodException: <init>()。

    /**
     * 压缩必须在硬裁剪之前发生，否则窗口先被 trim 掉，摘要就没有素材了。
     * 所以这里强制 trigger < maxMessages；用户把 max-messages 调回 20 时也不会出现"永不压缩"。
     */
    private static Compaction resolveCompaction(Compaction compaction, int maxMessages) {
        Compaction config = compaction == null ? new Compaction(null, null, null, null) : compaction;
        int trigger = config.triggerMessages() == null || config.triggerMessages() < 2
                ? Math.max(6, maxMessages * 2 / 3)
                : config.triggerMessages();
        if (trigger >= maxMessages) {
            trigger = Math.max(2, maxMessages - 1);
        }
        return new Compaction(config.enabled(), trigger, config.keepTurns(), config.maxSummaryChars());
    }

    /**
     * @param enabled          总开关；关掉就退回"按轮裁剪"
     * @param triggerMessages  窗口超过多少条时触发压缩
     * @param keepTurns        压缩时保留最近几个完整轮次
     * @param maxSummaryChars  摘要正文上限（字）
     */
    public record Compaction(Boolean enabled, Integer triggerMessages, Integer keepTurns, Integer maxSummaryChars) {

        public Compaction {
            enabled = enabled == null ? Boolean.TRUE : enabled;
            keepTurns = keepTurns == null || keepTurns < 1 ? FALLBACK_KEEP_TURNS : keepTurns;
            maxSummaryChars = maxSummaryChars == null || maxSummaryChars < 100
                    ? FALLBACK_SUMMARY_CHARS
                    : maxSummaryChars;
        }
    }
}
