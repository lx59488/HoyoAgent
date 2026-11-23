package com.luobo.hoyoagent.agent.transcript;

import java.util.List;

/**
 * 一轮对话在"写盘之前"的形态：用户说了什么、模型回了什么、调了哪些工具、失败原因。
 *
 * <p>{@code seq} 和 {@code at} 由 {@link TranscriptStore} 在追加时分配，调用方不需要知道。
 */
public record TranscriptExchange(String sessionId, String userId, String user,
                                 String assistant, List<String> tools, String error) {

    public TranscriptExchange {
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    public static TranscriptExchange ok(String sessionId, String userId, String user,
                                        String assistant, List<String> tools) {
        return new TranscriptExchange(sessionId, userId, user, assistant, tools, null);
    }

    /**
     * 模型调用失败也要记：memory 里那轮用户消息同样留着（框架在发请求前就 add 了），
     * 历史里缺一行反而会让"用户问了但没回答"看起来像丢消息。
     */
    public static TranscriptExchange failed(String sessionId, String userId, String user,
                                            List<String> tools, String error) {
        return new TranscriptExchange(sessionId, userId, user, null, tools, error);
    }
}
