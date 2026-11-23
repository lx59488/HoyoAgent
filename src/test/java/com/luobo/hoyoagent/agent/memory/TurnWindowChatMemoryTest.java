package com.luobo.hoyoagent.agent.memory;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TurnWindowChatMemoryTest {

    private final SessionMemoryStore store =
            new SessionMemoryStore(new MemoryProperties(20, 200, Duration.ofHours(2), null));

    private List<ChatMessage> messages(List<ChatMessage> history, int max) {
        List<ChatMessage> copy = new ArrayList<>(history);
        copy.add(0, SystemMessage.from("你是助手"));
        return new TurnWindowChatMemory("t", store, max).trim(copy);
    }

    @Test
    void 超窗时按轮丢_首条非system必须是USER() {
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            history.add(UserMessage.from("第 " + i + " 问"));
            history.add(AiMessage.from("第 " + i + " 答"));
        }

        List<ChatMessage> kept = messages(history, 5);

        assertThat(kept).hasSize(5);                     // 1 system + 2 轮
        assertThat(kept.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(kept.get(1)).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) kept.get(1)).singleText()).isEqualTo("第 1 问");
    }

    @Test
    void 工具轮整体保留_不会被切开() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("t1").name("checkinToday").arguments("{}").build();
        List<ChatMessage> history = new ArrayList<>();
        history.add(UserMessage.from("帮我签到"));
        history.add(AiMessage.builder().toolExecutionRequests(List.of(request)).build());
        history.add(ToolExecutionResultMessage.from(request, "【结论】签到成功"));
        history.add(AiMessage.from("三个游戏都签好了"));
        history.add(UserMessage.from("那累计呢"));
        history.add(AiMessage.from("一共 12 天"));

        List<ChatMessage> kept = messages(history, 20);

        assertThat(kept).hasSize(7);                     // 1 system + 4 + 2
        assertThat(kept).anyMatch(m -> m instanceof ToolExecutionResultMessage);
    }

    @Test
    void 放不下整轮时_宁可丢整轮也不留孤儿TOOL() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("t1").name("checkinToday").arguments("{}").build();
        List<ChatMessage> history = new ArrayList<>();
        history.add(UserMessage.from("帮我签到"));
        history.add(AiMessage.builder().toolExecutionRequests(List.of(request)).build());
        history.add(ToolExecutionResultMessage.from(request, "【结论】签到成功"));
        history.add(AiMessage.from("签好了"));
        history.add(UserMessage.from("那累计呢"));
        history.add(AiMessage.from("一共 12 天"));

        List<ChatMessage> kept = messages(history, 3);

        assertThat(kept).hasSize(3);                     // 1 system + 最后一轮 2 条
        assertThat(kept).noneMatch(m -> m instanceof ToolExecutionResultMessage);
        assertThat(kept.get(1)).isInstanceOf(UserMessage.class);
    }

    @Test
    void 超窗时先压缩再裁剪_摘要留在窗口头部() {
        MemoryProperties properties = new MemoryProperties(30, 200, Duration.ofHours(2),
                new MemoryProperties.Compaction(true, 6, 2, 200));
        SessionMemoryStore compactingStore = new SessionMemoryStore(properties);
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages) {
                return ChatResponse.builder().aiMessage(AiMessage.from("压缩后的摘要")).build();
            }
        };
        TurnWindowChatMemory memory = new TurnWindowChatMemory("t", compactingStore,
                properties.maxMessages(), new ConversationCompactor(model, properties));

        for (int i = 0; i < 5; i++) {
            memory.add(UserMessage.from("问题 " + i));
            memory.add(AiMessage.from("回答 " + i));
        }

        List<ChatMessage> stored = compactingStore.getMessages("t");
        assertThat(stored).hasSizeLessThan(10);
        assertThat(stored.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(((SystemMessage) stored.get(0)).text()).contains("压缩后的摘要");
    }
}