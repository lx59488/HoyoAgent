package com.luobo.hoyoagent.agent.transcript;

import java.util.List;

/**
 * 一条聊天历史，也是磁盘 JSONL 的一行。
 *
 * <p>字段刻意扁平：{@code at} 存 ISO-8601 UTC 字符串（不是 epoch），出问题时能直接看文件；
 * {@code seq} 在会话内从 1 开始递增，分页靠它而不是时间戳（同一毫秒内两轮不会翻乱）。
 *
 * @param conversationId 会话展示 id：私聊 {@code c2c:<openid>}，群聊 {@code group:<group_openid>}
 * @param kind           会话类型：{@code c2c} / {@code group} / {@code other}
 * @param assistant      模型回复；失败轮为 {@code null}
 * @param tools          本轮调用的工具名（只存名字，不存工具结果）
 * @param error          失败轮的错误摘要；成功轮为 {@code null}
 */
public record TranscriptTurn(long seq, String at, String sessionId, String userId,
                             String conversationId, String kind,
                             String user, String assistant, List<String> tools, String error) {
}
