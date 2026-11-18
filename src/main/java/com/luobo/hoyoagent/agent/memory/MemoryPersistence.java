package com.luobo.hoyoagent.agent.memory;

import dev.langchain4j.data.message.ChatMessage;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 记忆的持久化后端：只管存/取/删，不管窗口裁剪、也不管 TTL。
 * 后端越笨越好换——今天是文件，明天想换 SQLite 只动实现。
 */
public interface MemoryPersistence {

    Optional<Stored> load(String sessionId);

    void save(String sessionId, List<ChatMessage> messages, Instant updatedAt);

    void delete(String sessionId);

    record Stored(List<ChatMessage> messages, Instant updatedAt) {
    }
}