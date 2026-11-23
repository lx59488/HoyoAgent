package com.luobo.hoyoagent.agent.observability;

import com.luobo.hoyoagent.common.log.Mask;
import dev.langchain4j.model.output.TokenUsage;

import java.util.List;

/**
 * 一轮 Agent 的观测记录（对应 C4）。
 *
 * <p>为什么要有它：现在能回答"回复内容是什么"，但答不了"这轮花了多少 token、慢在哪、
 * 用的是哪版提示词"。而这三件事恰好是改提示词 / 换模型之前必须先知道的——
 * 没有它们，任何改动都无法归因（P3 的老教训：改完必须有跑批，否则"感觉更好"不算结论）。
 *
 * <p>刻意不做的事：不落库、不建表、不接 Micrometer。一行结构化日志就够用
 * （`grep [TRACE]` 就能捞全），等真的需要聚合查询再谈存储（`agent-architecture.md` §4）。
 */
public record TurnTrace(
        String traceId,
        String sessionId,
        String promptVersion,
        List<String> toolsCalled,
        int inputTokens,
        int outputTokens,
        long latencyMs,
        String outcome) {

    /** provider 没给用量时的取值。显式写 -1 而不是 0：0 是"真的没花钱"，那不可能。 */
    public static final int UNKNOWN_TOKENS = -1;

    public static TurnTrace ok(String traceId, String sessionId, String promptVersion,
                               List<String> toolsCalled, TokenUsage usage, long latencyMs) {
        return new TurnTrace(traceId, sessionId, promptVersion, toolsCalled,
                tokens(usage, true), tokens(usage, false), latencyMs, "OK");
    }

    public static TurnTrace failed(String traceId, String sessionId, String promptVersion,
                                   List<String> toolsCalled, TokenUsage usage, long latencyMs,
                                   String error) {
        return new TurnTrace(traceId, sessionId, promptVersion, toolsCalled,
                tokens(usage, true), tokens(usage, false), latencyMs,
                "ERROR:" + (error == null || error.isBlank() ? "unknown" : error));
    }

    /** 一行日志。session 走 {@link Mask}：会话 id 里可能带平台 openid，别直接打。 */
    public String logLine() {
        return "[TRACE] traceId=%s session=%s prompt=%s tools=%s tokensIn=%d tokensOut=%d latencyMs=%d outcome=%s"
                .formatted(traceId, Mask.id(sessionId), promptVersion,
                        toolsCalled.isEmpty() ? "-" : String.join(",", toolsCalled),
                        inputTokens, outputTokens, latencyMs, outcome);
    }

    private static int tokens(TokenUsage usage, boolean input) {
        if (usage == null) {
            return UNKNOWN_TOKENS;
        }
        Integer value = input ? usage.inputTokenCount() : usage.outputTokenCount();
        return value == null ? UNKNOWN_TOKENS : value;
    }
}
