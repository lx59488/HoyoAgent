package com.luobo.hoyoagent.agent.transcript;

import java.util.List;

/**
 * 完整聊天历史的存取口。
 *
 * <p>和 {@code MemoryStore} 的分工：MemoryStore 存的是"下一轮真正发给模型的窗口"（会被裁剪），
 * 这里存的是"用户到底聊过什么"（只追加，永不改写）。两者都在会话裁剪之前/之后各写一份，
 * 互不影响——清空记忆不会删历史，历史也不会被塞回模型。
 *
 * <p><b>只读约定</b>：{@link #sessions()} / {@link #read} 不 touch 会话、不续 TTL、不恢复任何状态；
 * 唯一的写入口是 {@link #append}，由 Agent 在每轮结束时调用。
 *
 * <p><b>失败约定</b>：{@link #append} 不允许把异常抛给聊天链路。历史是观测，不是业务事实，
 * 落盘失败只能降级为日志。
 */
public interface TranscriptStore {

    /** 追加一轮；实现必须自己吞掉 IO 异常。 */
    void append(TranscriptExchange exchange);

    /** 会话列表，按最后聊天时间倒序。 */
    List<TranscriptSession> sessions();

    /**
     * 读一段历史，返回 seq 升序。
     *
     * @param before 只取 seq 严格小于它的轮次；{@code <= 0} 表示从最新一轮往前取
     * @param limit  最多返回多少轮（实现负责夹到合理范围）
     */
    TranscriptPage read(String sessionId, long before, int limit);
}
