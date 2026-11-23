package com.luobo.hoyoagent.agent.memory;


import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import java.util.ArrayList;
import java.util.List;
/**
 * 按「轮」裁剪的窗口记忆，替代 MessageWindowChatMemory。
 *
 * <p>为什么换掉它：它是按<b>条</b>裁的。21 条裁到 20 条时正好淘汰一条 USER，
 * 而它对应的 AI(tool_calls) / TOOL / AI 全留着——模型看到的第一条变成
 * "没被问过就决定去签到"。这是 P7 发现③，不是模型的问题。
 *
 * <p>这里的不变量：
 * <ul>
 *   <li>一条 SYSTEM 也不裁（它常驻第一位）；</li>
 *   <li>每个 USER 开启一轮，轮内的 AI(tool_calls) / TOOL / AI 跟着整轮一起留或一起丢；</li>
 *   <li>宁可少留一轮，也不切断一轮——所以窗口通常是 "≤ maxMessages"，不是 "= maxMessages"。</li>
 * </ul>
 */
public final class TurnWindowChatMemory implements ChatMemory {

    private final Object id;
    private final ChatMemoryStore store;
    private final int maxMessages;
    private final ConversationCompactor compactor;

    /** 不压缩：只按轮裁剪，单测和"压缩关闭"的降级路径用它。 */
    public TurnWindowChatMemory(Object id, ChatMemoryStore store, int maxMessages) {
        this(id, store, maxMessages, null);
    }

    public TurnWindowChatMemory(Object id, ChatMemoryStore store, int maxMessages,
                                ConversationCompactor compactor) {
        this.id = id;
        this.store = store;
        this.maxMessages = Math.max(2, maxMessages);
        this.compactor = compactor;
    }

    @Override
    public Object id() {
        return id;
    }

    @Override
    public void add(ChatMessage message) {
        List<ChatMessage> messages = new ArrayList<>(store.getMessages(id));
        messages.add(message);
        // 必须先压缩再 trim：反过来旧轮已经被丢掉，摘要就没有素材了
        if (compactor != null) {
            messages = new ArrayList<>(compactor.compact(id, messages));
        }
        store.updateMessages(id, trim(messages));
    }

    @Override
    public List<ChatMessage> messages() {
        return store.getMessages(id);
    }

    @Override
    public void clear() {
        store.deleteMessages(id);
    }

    /** 包内可见：单测直接调它，不用真的往 store 里写。 */
    List<ChatMessage> trim(List<ChatMessage> messages) {
        if (messages.size() <= maxMessages) {
            return messages;
        }

        int head = 0;
        while (head < messages.size() && messages.get(head) instanceof SystemMessage) {
            head++;
        }
        List<ChatMessage> system = messages.subList(0, head);
        List<List<ChatMessage>> turns = splitTurns(messages.subList(head, messages.size()));

        int budget = maxMessages - system.size();
        List<ChatMessage> kept = new ArrayList<>();
        int used = 0;
        for (int i = turns.size() - 1; i >= 0; i--) {
            List<ChatMessage> turn = turns.get(i);
            // kept 为空时无条件收下：保证至少留一轮，哪怕这一轮本身就超窗
            if (used + turn.size() > budget && !kept.isEmpty()) {
                break;
            }
            kept.addAll(0, turn);
            used += turn.size();
        }

        List<ChatMessage> out = new ArrayList<>(system.size() + kept.size());
        out.addAll(system);
        out.addAll(kept);
        return List.copyOf(out);
    }

    /** 每个 USER 开启一轮；轮首那条不是 USER 的（崩溃残留的 TOOL 之类）自成一轮，最先被丢。 */
    static List<List<ChatMessage>> splitTurns(List<ChatMessage> messages) {
        List<List<ChatMessage>> turns = new ArrayList<>();
        List<ChatMessage> current = null;
        for (ChatMessage message : messages) {
            if (current == null || message instanceof UserMessage) {
                current = new ArrayList<>();
                turns.add(current);
            }
            current.add(message);
        }
        return turns;
    }
}
