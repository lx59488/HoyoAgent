package com.luobo.hoyoagent.agent.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锁住"内存不会涨、记忆不会串"的四条依据：会话数上限、空闲 TTL、删除即空、替换不追加。
 *
 * <p>窗口上限（maxMessages）不归这里管——那是 MessageWindowChatMemory 的职责，
 * 这里只管"消息存在哪、活多久"。
 *
 * <p>为什么用 Thread.sleep 而不是给生产代码注入 Clock：为了一个 20ms 的断言加一条时间源，
 * 引入的复杂度比它换来的可测性更贵。sleep 5ms 在 Windows 上粒度够用，也不需要 mock。
 *
 * <p>注意：{@code List.of(UserMessage.from("x"))} 会被推断成 {@code List<UserMessage>}，
 * 匹配不上 {@code List<ChatMessage>} 参数，所以下面统一写 {@code List.<ChatMessage>of(...)}。
 */
class SessionMemoryStoreTest {

    @Test
    void unknownSessionIsEmptyNotNull() {
        SessionMemoryStore store = store(200, Duration.ofHours(2));

        // 不是 null：调用方（MemoryController、MessageWindowChatMemory）都不做空判断
        assertThat(store.getMessages("nobody")).isEmpty();
        assertThat(store.snapshot("nobody")).isEmpty();
        assertThat(store.sessionIds()).isEmpty();
        // 没有会话 ≠ 过期：前者是首次访问，后者要显示在调试页上
        assertThat(store.expired("nobody")).isFalse();
    }

    @Test
    void messagesRoundTripInOrder() {
        SessionMemoryStore store = store(200, Duration.ofHours(2));
        List<ChatMessage> messages = List.of(
                SystemMessage.from("你是签到助手"),
                UserMessage.from("帮我签个到"));

        store.updateMessages("s1", messages);

        // 顺序不能变：OpenAI/DeepSeek 对消息顺序敏感，tool 结果必须紧跟发起它的 AI 消息
        assertThat(store.getMessages("s1")).isEqualTo(messages);
        assertThat(store.snapshot("s1")).hasSize(2);
    }

    @Test
    void updateReplacesInsteadOfAppending() {
        SessionMemoryStore store = store(200, Duration.ofHours(2));
        store.updateMessages("s1", List.<ChatMessage>of(UserMessage.from("第一版")));

        store.updateMessages("s1", List.<ChatMessage>of(UserMessage.from("第二版")));

        // store 是"整份写入"，不是"追加"——框架每次都给完整的窗口
        assertThat(store.getMessages("s1")).containsExactly(UserMessage.from("第二版"));
    }

    @Test
    void emptyUpdateDropsSession() {
        SessionMemoryStore store = store(200, Duration.ofHours(2));
        store.updateMessages("s1", List.<ChatMessage>of(UserMessage.from("hi")));

        store.updateMessages("s1", List.of());

        // 空窗口 = 没有会话。留着空壳会让 sessionIds() 越跑越多
        assertThat(store.snapshot("s1")).isEmpty();
        assertThat(store.sessionIds()).isEmpty();
    }

    @Test
    void deleteRemovesSession() {
        SessionMemoryStore store = store(200, Duration.ofHours(2));
        store.updateMessages("s1", List.<ChatMessage>of(UserMessage.from("hi")));

        store.deleteMessages("s1");

        assertThat(store.snapshot("s1")).isEmpty();
        assertThat(store.sessionIds()).isEmpty();
    }

    @Test
    void idleSessionIsDiscardedOnRead() throws InterruptedException {
        SessionMemoryStore store = store(200, Duration.ofMillis(10));
        store.updateMessages("s1", List.<ChatMessage>of(UserMessage.from("hi")));

        Thread.sleep(20);

        assertThat(store.getMessages("s1")).isEmpty();
        // 关键：不是"返回空"，是"真的丢掉了"，否则 [MEMORY] 那行日志就是空转
        assertThat(store.sessionIds()).isEmpty();
    }

    @Test
    void readingRefreshesIdleTimer() throws InterruptedException {
        SessionMemoryStore store = store(200, Duration.ofMillis(200));
        store.updateMessages("s1", List.<ChatMessage>of(UserMessage.from("hi")));

        Thread.sleep(50);
        assertThat(store.getMessages("s1")).hasSize(1);   // 这一读会把 lastAccess 推到此刻

        Thread.sleep(50);
        // 距写入已 100ms（< 200ms），距上次读只 50ms —— 活着说明 touch() 生效了
        assertThat(store.getMessages("s1")).hasSize(1);
    }

    @Test
    void oldestSessionIsEvictedWhenOverCapacity() throws InterruptedException {
        SessionMemoryStore store = store(2, Duration.ofHours(1));

        store.updateMessages("a", List.<ChatMessage>of(UserMessage.from("a")));
        Thread.sleep(5);
        store.updateMessages("b", List.<ChatMessage>of(UserMessage.from("b")));
        Thread.sleep(5);
        store.updateMessages("c", List.<ChatMessage>of(UserMessage.from("c")));

        // 超过上限淘汰"最久未用"，不是"最先创建"——这两者在有读操作时会分叉
        assertThat(store.sessionIds()).containsExactlyInAnyOrder("b", "c");
        assertThat(store.snapshot("a")).isEmpty();
    }

    @Test
    void readingProtectsSessionFromEviction() throws InterruptedException {
        SessionMemoryStore store = store(2, Duration.ofHours(1));

        store.updateMessages("a", List.<ChatMessage>of(UserMessage.from("a")));
        Thread.sleep(5);
        store.updateMessages("b", List.<ChatMessage>of(UserMessage.from("b")));
        Thread.sleep(5);
        store.getMessages("a");                            // a 被读，变成最新
        Thread.sleep(5);
        store.updateMessages("c", List.<ChatMessage>of(UserMessage.from("c")));

        // 该淘汰的是 b：如果实现写成了 FIFO，这里会变成 a 被淘汰
        assertThat(store.sessionIds()).containsExactlyInAnyOrder("a", "c");
    }

    @Test
    void expiredReportsIdleTimeoutWithoutTouchingTheSession() throws InterruptedException {
        SessionMemoryStore store = store(200, Duration.ofMillis(80));
        store.updateMessages("s1", List.<ChatMessage>of(UserMessage.from("hi")));

        Thread.sleep(50);
        assertThat(store.expired("s1")).isFalse();

        // 看一眼调试接口不该续命：如果 expired() 里也调了 touch()，下面这句会变成 false
        Thread.sleep(50);

        assertThat(store.expired("s1")).isTrue();
        // 但内容还在：调试页要能看见这条会话长什么样，同时知道它下一轮会被丢掉
        assertThat(store.snapshot("s1")).hasSize(1);
    }

    @Test
    void snapshotIgnoresIdleTimeoutOnPurpose() throws InterruptedException {
        SessionMemoryStore store = store(200, Duration.ofMillis(10));
        store.updateMessages("s1", List.<ChatMessage>of(UserMessage.from("hi")));

        Thread.sleep(20);

        // 注意顺序：先看 snapshot，再看 getMessages —— 后者会把会话删掉
        // 已知不对称：调试接口看得见，模型下一轮拿到的是空。
        // 这个不对称不能靠改 snapshot 解决（看一眼就删会让调试页变成自毁按钮），
        // 所以由 expired() 显式暴露，调试页据此显示"已过期"，而不是假装还有记忆。
        assertThat(store.snapshot("s1")).hasSize(1);
        assertThat(store.getMessages("s1")).isEmpty();
    }

    private static SessionMemoryStore store(int maxSessions, Duration idleTtl) {
        return new SessionMemoryStore(new MemoryProperties(20, maxSessions, idleTtl, null));
    }
}