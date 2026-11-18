package com.luobo.hoyoagent.agent.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话记忆的唯一真相。
 *
 * <p>为什么状态只能放这里：{@code ChatMemoryProvider} 只在"第一次见到某个 memoryId"时被调用一次，
 * 返回的 ChatMemory 被框架永久持有（ChatMemoryService 里就是一个没有淘汰的 ConcurrentHashMap）。
 * 状态一旦放进 provider 的局部变量或 ChatMemory 实例，淘汰就无从谈起。
 *
 * <p>两层淘汰，别混为一谈：
 * <ul>
 *   <li>本类管"消息正文"：会话数上限 + 空闲 TTL，这是占内存的大头；</li>
 *   <li>框架那层只剩一个几百字节的 ChatMemory 对象，靠 ChatMemoryAccess#evictChatMemory 清。</li>
 * </ul>
 */
@Component
public class SessionMemoryStore implements ChatMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(SessionMemoryStore.class);

    private final int maxSessions;

    private final Duration idleTtl;

    private final MemoryPersistence persistence;

    private final Map<Object, Slot> slots = new ConcurrentHashMap<>();

    /** 单测用：只有内存，不落盘。 */
    public SessionMemoryStore(MemoryProperties properties) {
        this(properties, null);
    }

    @Autowired
    public SessionMemoryStore(MemoryProperties properties, MemoryPersistence persistence) {
        this.maxSessions = properties.maxSessions();
        this.idleTtl = properties.idleTtl();
        this.persistence = persistence;
    }


    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        Slot slot = slots.get(memoryId);
        if (slot == null) {
            slot = restore(memoryId);
            if (slot == null) {
                return List.of();
            }
        }
        if (idleSince(slot.lastAccess())) {
            if (slots.remove(memoryId, slot)) {
                log.info("[MEMORY] 会话空闲超时，丢弃 session={} messages={}",
                        mask(memoryId), slot.size());
            }
            return List.of();
        }
        slot.touch();
        return slot.snapshot();
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            slots.remove(memoryId);                 // 和 deleteMessages 保持一致
            if (persistence != null) {
                persistence.delete(String.valueOf(memoryId));
            }
            return;
        }
        Slot slot = slots.computeIfAbsent(memoryId, key -> new Slot());
        slot.replace(messages);
        if (persistence != null) {
            persistence.save(String.valueOf(memoryId), messages, slot.lastAccess());
        }
        evictOverflow();
    }

    @Override
    public void deleteMessages(Object memoryId) {
        slots.remove(memoryId);
        if (persistence != null) {
            persistence.delete(String.valueOf(memoryId));
        }
    }

    /** 新进程第一次见到这个会话：内存 miss 才读盘。 */
    private Slot restore(Object memoryId) {
        if (persistence == null) {
            return null;
        }
        Optional<MemoryPersistence.Stored> stored = persistence.load(String.valueOf(memoryId));
        if (stored.isEmpty() || stored.get().messages().isEmpty()) {
            return null;
        }
        Slot slot = new Slot();
        slot.replaceAt(stored.get().messages(), stored.get().updatedAt());
        if (idleSince(slot.lastAccess())) {
            log.info("[MEMORY] 磁盘上的会话已空闲超时，当作新会话 session={}", mask(memoryId));
            return null;
        }
        slots.put(memoryId, slot);
        log.info("[MEMORY] 从磁盘恢复会话 session={} messages={}", mask(memoryId), slot.size());
        return slot;
    }

    /** 只读快照，给调试接口和 Controller 用；不做 TTL 判断，免得"看一眼"把会话踩死。 */
    public List<ChatMessage> snapshot(Object memoryId) {
        Slot slot = slots.get(memoryId);
        return slot == null ? List.of() : slot.snapshot();
    }

    /**
     * 会话是否已空闲超时。
     *
     * <p>存在的意义只有一个：{@link #snapshot} 故意不做过期判断，于是"调试页看得见 7 条消息"
     * 和"模型下一轮收到 0 条"会同时为真。把这件事显式说出来，比让人对着调试页猜失忆强。
     *
     * <p>两条约定：
     * <ul>
     *   <li>只看不碰——这里不调 {@code touch()}，否则打开调试页就等于给会话续命，TTL 永远不生效；</li>
     *   <li>不存在返回 false——"没有会话"是首次访问，不是过期。</li>
     * </ul>
     */
    public boolean expired(Object memoryId) {
        Slot slot = slots.get(memoryId);
        return slot != null && idleSince(slot.lastAccess());
    }

    public Collection<Object> sessionIds() {
        return List.copyOf(slots.keySet());
    }

    private boolean idleSince(Instant at) {
        return Duration.between(at, Instant.now()).compareTo(idleTtl) > 0;
    }

    private void evictOverflow() {
        while (slots.size() > maxSessions) {
            Map.Entry<Object, Slot> oldest = slots.entrySet().stream()
                    .min(Comparator.comparing(entry -> entry.getValue().lastAccess()))
                    .orElse(null);
            if (oldest == null) {
                return;
            }
            slots.remove(oldest.getKey(), oldest.getValue());
            log.info("[MEMORY] 会话数超过 {}，淘汰最久未用 session={}",
                    maxSessions, mask(oldest.getKey()));
        }
    }

    /** 日志里不出现完整会话 id：将来 onebot 接入后它可能含 QQ 号。 */
    private static String mask(Object memoryId) {
        String text = String.valueOf(memoryId);
        return text.length() <= 6 ? "***" : text.substring(0, 6) + "***";
    }



    /** 一条会话：消息不可变、替换而非追加修改。 */
    private static final class Slot {

        private volatile List<ChatMessage> messages = List.of();

        private volatile Instant lastAccess = Instant.now();

        List<ChatMessage> snapshot() {
            return messages;
        }

        int size() {
            return messages.size();
        }

        Instant lastAccess() {
            return lastAccess;
        }

        void touch() {
            lastAccess = Instant.now();
        }

        void replace(List<ChatMessage> newMessages) {
            messages = List.copyOf(newMessages);
            lastAccess = Instant.now();
        }

        void replaceAt(List<ChatMessage> newMessages, Instant at) {
            messages = List.copyOf(newMessages);
            lastAccess = at;
        }
    }
}
