package com.luobo.hoyoagent.adapter.http;

import com.luobo.hoyoagent.agent.MihoyoAssistant;
import com.luobo.hoyoagent.agent.memory.SessionMemoryStore;
import com.luobo.hoyoagent.common.result.Result;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会话记忆的观测口。
 *
 * <p>不是为了"方便"：没有它，P7-5（裁剪到底丢了什么）和 P7-4（连续 30 轮不爆）
 * 都只能靠猜。返回的是 store 里的内容本身，也就是下一轮真正会发给模型的东西。
 */
@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    private final SessionMemoryStore memoryStore;

    private final MihoyoAssistant assistant;

    public MemoryController(SessionMemoryStore memoryStore, MihoyoAssistant assistant) {
        this.memoryStore = memoryStore;
        this.assistant = assistant;
    }

    @GetMapping("/{sessionId}")
    public Result<SessionView> peek(@PathVariable("sessionId") String sessionId) {
        List<ChatMessage> messages = memoryStore.snapshot(sessionId);
        return Result.success(new SessionView(sessionId, messages.size(),
                messages.stream().map(MemoryController::view).toList()));
    }

    /** 清空一条会话：先删消息，再让框架放掉那个 ChatMemory 实例。 */
    @DeleteMapping("/{sessionId}")
    public Result<SessionView> clear(@PathVariable("sessionId") String sessionId) {
        memoryStore.deleteMessages(sessionId);
        assistant.evictChatMemory(sessionId);
        return Result.success(new SessionView(sessionId, 0, List.of()));
    }

    private static MessageView view(ChatMessage message) {
        return new MessageView(message.type().name(), truncate(describe(message)));
    }

    private static String describe(ChatMessage message) {
        return switch (message) {
            case UserMessage user -> user.hasSingleText() ? user.singleText() : "[多段内容]";
            case AiMessage ai -> ai.hasToolExecutionRequests()
                    ? "[调用工具] " + ai.toolExecutionRequests().stream()
                    .map(ToolExecutionRequest::name).toList()
                    : orEmpty(ai.text());
            case SystemMessage system -> system.text();
            case ToolExecutionResultMessage tool -> tool.toolName() + " → " + tool.text();
            default -> message.toString();
        };
    }

    private static String orEmpty(String text) {
        return text == null ? "" : text;
    }

    private static String truncate(String text) {
        String oneLine = orEmpty(text).replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 120 ? oneLine : oneLine.substring(0, 120) + "…";
    }

    public record SessionView(String sessionId, int messages, List<MessageView> content) {
    }

    public record MessageView(String type, String text) {
    }
}