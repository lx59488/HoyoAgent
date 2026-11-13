package com.luobo.hoyoagent.agent.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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

    private final Map<Object, Slot> slots = new ConcurrentHashMap<>();

    public SessionMemoryStore(MemoryProperties properties) {
        this.maxSessions = properties.maxSessions();
        this.idleTtl = properties.idleTtl();
    }


    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        Slot slot = slots.get(memoryId);
        if (slot == null) {
            return List.of();
        }
        if (System.nanoTime() - slot.lastAccessNanos() > idleTtl.toNanos()) {
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
            slots.remove(memoryId);
            return;
        }
        slots.computeIfAbsent(memoryId, key -> new Slot()).replace(messages);
        evictOverflow();
    }

    @Override
    public void deleteMessages(Object memoryId) {
        slots.remove(memoryId);
    }

    /** 只读快照，给调试接口和 Controller 用；不做 TTL 判断，免得"看一眼"把会话踩死。 */
    public List<ChatMessage> snapshot(Object memoryId) {
        Slot slot = slots.get(memoryId);
        return slot == null ? List.of() : slot.snapshot();
    }

    public Collection<Object> sessionIds() {
        return List.copyOf(slots.keySet());
    }

    private void evictOverflow() {
        while (slots.size() > maxSessions) {
            Map.Entry<Object, Slot> oldest = slots.entrySet().stream()
                    .min(Comparator.comparingLong(entry -> entry.getValue().lastAccessNanos()))
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



    /** 一条会话：消息不可变、替换而非追加修改；最后访问时间用单调时钟。 */
    private static final class Slot {

        private volatile List<ChatMessage> messages = List.of();

        private volatile long lastAccessNanos = System.nanoTime();

        List<ChatMessage> snapshot() {
            return messages;
        }

        int size() {
            return messages.size();
        }

        long lastAccessNanos() {
            return lastAccessNanos;
        }

        void touch() {
            lastAccessNanos = System.nanoTime();
        }

        void replace(List<ChatMessage> newMessages) {
            messages = List.copyOf(newMessages);
            lastAccessNanos = System.nanoTime();
        }
    }
}
