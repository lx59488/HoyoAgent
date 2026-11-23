package com.luobo.hoyoagent.agent.transcript;

/**
 * 会话列表里的一行：谁、聊了多少轮、最后一次是什么时候、最后一句说了什么。
 *
 * @param conversationId 会话展示 id：私聊 {@code c2c:<openid>}，群聊 {@code group:<group_openid>}
 * @param kind           会话类型：{@code c2c} / {@code group} / {@code other}
 * @param turns          可读的轮数（坏行不计）
 * @param lastAt         最后一轮的 ISO-8601 UTC 时间
 * @param preview        最后一轮用户消息的单行截断，方便在列表里认出是谁
 */
public record TranscriptSession(String sessionId, String userId, String conversationId, String kind,
                                int turns, String lastAt, String preview) {
}
