package com.luobo.hoyoagent.agent.memory;

import com.luobo.hoyoagent.common.log.Mask;
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

    private final MemoryStore persistence;

    private final Map<Object, Slot> slots = new ConcurrentHashMap<>();

    /** 单测用：只有内存，不落盘。 */
    public SessionMemoryStore(MemoryProperties properties) {
        this(properties, null);
    }

    @Autowired
    public SessionMemoryStore(MemoryProperties properties, MemoryStore persistence) {
        this.maxSessions = properties.maxSessions();
        this.idleTtl = properties.idleTtl();
        this.persistence = persistence;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        // 用 compute 把「读盘 → 判过期 → touch」压成一个原子步骤：
        // 否则并发的 updateMessages 可能在过期判断之后写入，又被这里的 remove 丢掉。
        Slot slot = slots.compute(memoryId, (key, existing) -> {
            Slot current = existing != null ? existing : load(key);
            if (current == null) {
                return null;
            }
            if (idleSince(current.lastAccess())) {
                log.info("[MEMORY] 会话空闲超时，丢弃 session={} messages={}",
                        Mask.id(String.valueOf(key)), current.size());
                return null;
            }
            current.touch();
            return current;
        });
        return slot == null ? List.of() : slot.snapshot();
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            deleteMessages(memoryId);
            return;
        }
        // 换一个新 Slot 而不是原地改：compute 的映射要么整体生效，要么整体不生效。
        Slot updated = slots.compute(memoryId, (key, existing) -> {
            Slot next = new Slot();
            next.replace(messages);
            return next;
        });
        if (persistence != null) {
            persistence.save(String.valueOf(memoryId), messages, updated.lastAccess());
        }
        evictOverflow();
    }

    @Override
    public void deleteMessages(Object memoryId) {
        slots.compute(memoryId, (key, existing) -> null);
        if (persistence != null) {
            persistence.delete(String.valueOf(memoryId));
        }
    }

    /**
     * 新进程第一次见到这个会话：内存 miss 才读盘。返回的 Slot 由调用方放进 map，
     * 这里不 put——在 compute 的映射函数里再 put 同一个 key 会破坏原子性。
     */
    private Slot load(Object memoryId) {
        if (persistence == null) {
            return null;
        }
        Optional<MemoryStore.Stored> stored = persistence.load(String.valueOf(memoryId));
        if (stored.isEmpty() || stored.get().messages().isEmpty()) {
            return null;
        }
        Slot slot = new Slot();
        slot.replaceAt(stored.get().messages(), stored.get().updatedAt());
        if (idleSince(slot.lastAccess())) {
            log.info("[MEMORY] 磁盘上的会话已空闲超时，当作新会话 session={}", Mask.id(String.valueOf(memoryId)));
            return null;
        }
        log.info("[MEMORY] 从磁盘恢复会话 session={} messages={}", Mask.id(String.valueOf(memoryId)), slot.size());
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
                    maxSessions, Mask.id(String.valueOf(oldest.getKey())));
        }
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
