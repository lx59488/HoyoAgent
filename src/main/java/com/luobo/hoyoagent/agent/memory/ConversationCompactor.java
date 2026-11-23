package com.luobo.hoyoagent.agent.memory;

import com.luobo.hoyoagent.common.log.Mask;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 把「更早的完整轮次」压成一段摘要，替代直接丢弃。
 *
 * <p>为什么放在记忆写入路径上：{@link TurnWindowChatMemory} 在 add 后立刻 trim，
 * 等 AgentChatService 拿到回复时旧消息已经没了。要"压缩而不是丢"，就必须在 trim 之前动手。
 *
 * <p>摘要以 {@link SystemMessage} 存在窗口头部：
 * <ul>
 *   <li>{@code TurnWindowChatMemory} 本来就不裁 SYSTEM，放头部不会被当成一轮；</li>
 *   <li>{@code ChatMessageSerializer} 原生支持 SystemMessage，落盘不需要改格式；</li>
 *   <li>它不会被 {@code splitTurns} 切开，模型看到的是"系统摘要 + 最近几轮"。</li>
 * </ul>
 *
 * <p>失败策略：摘要调用失败、返回空、或压完不比原来短，都原样返回，让后面的 trim 兜底。
 * 压缩是"尽力记住更多"，不是业务事实，不能因为它把整轮聊天打挂。
 */
@Component
public class ConversationCompactor {

    /** 只有 SYSTEM 且以它开头才算"上一版摘要"；用户消息不可能带到 SYSTEM 里。 */
    static final String SUMMARY_PREFIX = "【更早对话摘要】";

    private static final Logger log = LoggerFactory.getLogger(ConversationCompactor.class);

    /** 单条消息喂给摘要器的上限，防止一条超长工具结果吃光输入。 */
    private static final int MAX_MESSAGE_CHARS = 1500;

    /** 一次摘要调用的输入上限；超过就分块，依次把上一块的摘要带进下一块。 */
    private static final int MAX_INPUT_CHARS = 12000;

    private final ChatModel model;

    private final MemoryProperties.Compaction config;

    private final String systemPrompt;

    public ConversationCompactor(ChatModel model, MemoryProperties properties) {
        this.model = model;
        this.config = properties.compaction();
        this.systemPrompt = """
                你是对话记忆压缩器。把「更早的对话」合并进「已有摘要」，输出一段紧凑的事实性中文摘要。
                保留：用户身份与称呼、偏好与约束、已确认的事实与结论、未完成的事项、工具调用及结果、关键数字与日期。
                丢弃：寒暄、重复、与后续无关的细节。
                不要编造，不要给建议，不要用 Markdown 标题，不要输出摘要以外的内容。
                摘要不超过 %d 字。
                """.formatted(config.maxSummaryChars());
    }

    public boolean enabled() {
        return config.enabled();
    }

    /**
     * 超过阈值时返回「摘要 + 最近 keepTurns 轮」；否则原样返回。
     * 任何异常都在这里吞掉并记日志，调用方不需要 try。
     */
    public List<ChatMessage> compact(Object memoryId, List<ChatMessage> messages) {
        if (!config.enabled() || messages.size() <= config.triggerMessages()) {
            return messages;
        }
        try {
            return doCompact(memoryId, messages);
        } catch (RuntimeException e) {
            log.warn("[MEMORY] 摘要压缩失败，退回窗口裁剪 session={} ({})",
                    Mask.id(String.valueOf(memoryId)), e.toString());
            return messages;
        }
    }

    private List<ChatMessage> doCompact(Object memoryId, List<ChatMessage> messages) {
        int head = 0;
        while (head < messages.size() && messages.get(head) instanceof SystemMessage) {
            head++;
        }
        List<ChatMessage> leading = messages.subList(0, head);
        String previous = previousSummary(leading);
        List<ChatMessage> keepSystem = leading.stream()
                .filter(message -> !isSummary(message))
                .toList();

        List<List<ChatMessage>> turns = TurnWindowChatMemory.splitTurns(messages.subList(head, messages.size()));
        if (turns.size() <= config.keepTurns()) {
            // 全是最近轮次：没有完整的旧轮可压，交给 trim 处理
            return messages;
        }
        int keepFrom = turns.size() - config.keepTurns();
        List<ChatMessage> older = new ArrayList<>();
        for (int i = 0; i < keepFrom; i++) {
            older.addAll(turns.get(i));
        }
        List<ChatMessage> kept = new ArrayList<>();
        for (int i = keepFrom; i < turns.size(); i++) {
            kept.addAll(turns.get(i));
        }

        String summary = summarize(previous, older);
        if (summary == null || summary.isBlank()) {
            return messages;
        }
        summary = truncate(summary.strip(), config.maxSummaryChars());

        List<ChatMessage> out = new ArrayList<>(keepSystem.size() + 1 + kept.size());
        out.addAll(keepSystem);
        out.add(SystemMessage.from(SUMMARY_PREFIX + summary));
        out.addAll(kept);
        if (out.size() >= messages.size()) {
            // 摘要本身太长、旧轮太少：换进去不省地方，不如让 trim 按原策略走
            return messages;
        }
        log.info("[MEMORY] 压缩记忆 session={} {} 条 → {} 条，摘要 {} 字",
                Mask.id(String.valueOf(memoryId)), messages.size(), out.size(), summary.length());
        return List.copyOf(out);
    }

    /** 分块压缩：每块都带上一块的摘要，保证所有旧内容都被看到，而不是只取最近一截。 */
    private String summarize(String previous, List<ChatMessage> older) {
        String summary = previous;
        for (String chunk : chunks(older)) {
            summary = callModel(summary, chunk);
            if (summary == null || summary.isBlank()) {
                return null;
            }
            summary = truncate(summary.strip(), config.maxSummaryChars());
        }
        return summary;
    }

    private String callModel(String previous, String chunk) {
        String userPrompt = "已有摘要：\n"
                + (previous == null || previous.isBlank() ? "（无）" : previous)
                + "\n\n更早的对话：\n"
                + chunk;
        ChatResponse response = model.chat(List.of(
                SystemMessage.from(systemPrompt),
                UserMessage.from(userPrompt)));
        if (response == null || response.aiMessage() == null) {
            return null;
        }
        return response.aiMessage().text();
    }

    private static List<String> chunks(List<ChatMessage> messages) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (ChatMessage message : messages) {
            String line = render(message);
            if (line.isBlank()) {
                continue;
            }
            if (current.length() > 0 && current.length() + line.length() + 1 > MAX_INPUT_CHARS) {
                chunks.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(line);
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    private static String render(ChatMessage message) {
        return switch (message) {
            case UserMessage user -> "USER: " + truncate(
                    user.hasSingleText() ? user.singleText() : "[多段内容]", MAX_MESSAGE_CHARS);
            case AiMessage ai -> ai.hasToolExecutionRequests()
                    ? "ASSISTANT: [调用工具] " + ai.toolExecutionRequests().stream()
                    .map(request -> request.name() + "(" + truncate(request.arguments(), 200) + ")")
                    .toList()
                    : "ASSISTANT: " + truncate(orEmpty(ai.text()), MAX_MESSAGE_CHARS);
            case ToolExecutionResultMessage tool ->
                    "TOOL(" + tool.toolName() + "): " + truncate(tool.text(), MAX_MESSAGE_CHARS);
            case SystemMessage system -> "SYSTEM: " + truncate(orEmpty(system.text()), MAX_MESSAGE_CHARS);
            default -> "";
        };
    }

    private static String previousSummary(List<ChatMessage> leading) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage message : leading) {
            if (!isSummary(message)) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(((SystemMessage) message).text().substring(SUMMARY_PREFIX.length()).strip());
        }
        return sb.toString().strip();
    }

    private static boolean isSummary(ChatMessage message) {
        return message instanceof SystemMessage system
                && system.text() != null
                && system.text().startsWith(SUMMARY_PREFIX);
    }

    private static String orEmpty(String text) {
        return text == null ? "" : text;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
