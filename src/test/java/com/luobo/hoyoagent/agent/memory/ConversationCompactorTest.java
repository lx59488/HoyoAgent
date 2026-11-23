package com.luobo.hoyoagent.agent.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationCompactorTest {

    @Test
    void 超过阈值时用摘要替换最早的完整轮次() {
        FakeModel model = new FakeModel(prompt -> "用户问过问题 0 到 2");
        ConversationCompactor compactor = new ConversationCompactor(model, props(true, 6, 2, 200));
        List<ChatMessage> input = history(5);

        List<ChatMessage> out = compactor.compact("s1", input);

        assertThat(out).hasSize(1 + 4);
        assertThat(out.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(((SystemMessage) out.get(0)).text())
                .isEqualTo(ConversationCompactor.SUMMARY_PREFIX + "用户问过问题 0 到 2");
        assertThat(out.subList(1, 5)).containsExactlyElementsOf(input.subList(6, 10));
        assertThat(model.calls).hasSize(1);
        assertThat(model.userPrompt(0))
                .contains("问题 0", "问题 2")
                .doesNotContain("问题 3");
    }

    @Test
    void 低于阈值不动窗口也不调用模型() {
        FakeModel model = new FakeModel(prompt -> "不该被调用");
        ConversationCompactor compactor = new ConversationCompactor(model, props(true, 10, 1, 200));
        List<ChatMessage> input = history(2);

        assertThat(compactor.compact("s1", input)).isSameAs(input);
        assertThat(model.calls).isEmpty();
    }

    @Test
    void 关闭压缩时保持原窗口() {
        FakeModel model = new FakeModel(prompt -> "不该被调用");
        ConversationCompactor compactor = new ConversationCompactor(model, props(false, 6, 2, 200));
        List<ChatMessage> input = history(5);

        assertThat(compactor.compact("s1", input)).isSameAs(input);
        assertThat(model.calls).isEmpty();
    }

    @Test
    void 模型失败时原样返回而不是把聊天打挂() {
        FakeModel model = new FakeModel(prompt -> {
            throw new IllegalStateException("boom");
        });
        ConversationCompactor compactor = new ConversationCompactor(model, props(true, 6, 2, 200));
        List<ChatMessage> input = history(5);

        assertThat(compactor.compact("s1", input)).isSameAs(input);
    }

    @Test
    void 上一版摘要会合并进新摘要() {
        FakeModel model = new FakeModel(prompt -> "新摘要");
        ConversationCompactor compactor = new ConversationCompactor(model, props(true, 6, 2, 200));
        List<ChatMessage> input = new ArrayList<>();
        input.add(SystemMessage.from(ConversationCompactor.SUMMARY_PREFIX + "旧摘要"));
        input.addAll(history(5));

        List<ChatMessage> out = compactor.compact("s1", input);

        assertThat(model.userPrompt(0)).contains("已有摘要", "旧摘要");
        List<ChatMessage> summaries = out.stream()
                .filter(message -> message instanceof SystemMessage system
                        && system.text().startsWith(ConversationCompactor.SUMMARY_PREFIX))
                .toList();
        assertThat(summaries).hasSize(1);
        assertThat(((SystemMessage) summaries.get(0)).text()).isEqualTo(ConversationCompactor.SUMMARY_PREFIX + "新摘要");
    }

    @Test
    void 摘要为空时不替换() {
        FakeModel model = new FakeModel(prompt -> "   ");
        ConversationCompactor compactor = new ConversationCompactor(model, props(true, 6, 2, 200));
        List<ChatMessage> input = history(5);

        assertThat(compactor.compact("s1", input)).isSameAs(input);
    }

    @Test
    void 摘要超长会截断() {
        FakeModel model = new FakeModel(prompt -> "x".repeat(300));
        ConversationCompactor compactor = new ConversationCompactor(model, props(true, 6, 2, 100));
        List<ChatMessage> input = history(5);

        List<ChatMessage> out = compactor.compact("s1", input);

        String text = ((SystemMessage) out.get(0)).text();
        assertThat(text).endsWith("…");
        assertThat(text).hasSize(ConversationCompactor.SUMMARY_PREFIX.length() + 101);
    }

    @Test
    void 旧内容超过单次输入上限时分块压缩() {
        AtomicInteger counter = new AtomicInteger();
        FakeModel model = new FakeModel(prompt -> "摘要" + counter.incrementAndGet());
        ConversationCompactor compactor = new ConversationCompactor(model, props(true, 6, 2, 800));
        List<ChatMessage> input = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            input.add(UserMessage.from("问题" + i + " " + "长".repeat(1500)));
            input.add(AiMessage.from("回答" + i + " " + "答".repeat(1500)));
        }

        List<ChatMessage> out = compactor.compact("s1", input);

        assertThat(model.calls).hasSizeGreaterThanOrEqualTo(2);
        assertThat(model.userPrompt(1)).contains("已有摘要", "摘要1");
        assertThat(out).hasSize(1 + 4);
    }

    @Test
    void 全是最近轮次时宁可不压() {
        FakeModel model = new FakeModel(prompt -> "不该被调用");
        ConversationCompactor compactor = new ConversationCompactor(model, props(true, 3, 3, 200));
        List<ChatMessage> input = history(2);

        assertThat(compactor.compact("s1", input)).isSameAs(input);
        assertThat(model.calls).isEmpty();
    }

    private static MemoryProperties props(boolean enabled, int trigger, int keepTurns, int summaryChars) {
        return new MemoryProperties(30, 200, Duration.ofHours(2),
                new MemoryProperties.Compaction(enabled, trigger, keepTurns, summaryChars));
    }

    private static List<ChatMessage> history(int turns) {
        List<ChatMessage> messages = new ArrayList<>();
        for (int i = 0; i < turns; i++) {
            messages.add(UserMessage.from("问题 " + i));
            messages.add(AiMessage.from("回答 " + i));
        }
        return messages;
    }

    private static final class FakeModel implements ChatModel {

        private final Function<String, String> responder;

        private final List<List<ChatMessage>> calls = new ArrayList<>();

        private FakeModel(Function<String, String> responder) {
            this.responder = responder;
        }

        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            calls.add(List.copyOf(messages));
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(responder.apply(userPrompt(calls.size() - 1))))
                    .build();
        }

        private String userPrompt(int index) {
            return ((UserMessage) calls.get(index).get(1)).singleText();
        }
    }
}
