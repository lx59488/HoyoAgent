package com.luobo.hoyoagent.agent.transcript;

import java.util.List;

/**
 * 一次分页读取的结果：按 seq 升序返回 {@code turns}，{@code hasMore} 表示还有更早的轮次。
 */
public record TranscriptPage(String sessionId, String userId,
                             List<TranscriptTurn> turns, boolean hasMore) {
}
