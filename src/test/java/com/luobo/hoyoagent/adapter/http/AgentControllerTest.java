package com.luobo.hoyoagent.adapter.http;

import com.luobo.hoyoagent.agent.AgentChatService;
import com.luobo.hoyoagent.channel.SessionSerializer;
import com.luobo.hoyoagent.common.result.ApiResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentControllerTest {

    @Test
    void sameSessionIsSerialized() throws Exception {
        AgentChatService chat = mock(AgentChatService.class);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        when(chat.ask(anyString(), isNull(), anyString())).thenAnswer(invocation -> {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            firstEntered.countDown();
            release.await(5, TimeUnit.SECONDS);
            inFlight.decrementAndGet();
            return new AgentChatService.Answer("ok", List.of(), 1);
        });

        AgentController controller = new AgentController(chat, new SessionSerializer());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ApiResult<AgentController.AgentAnswer>> first =
                    pool.submit(() -> controller.agent("a", "session-1"));
            assertThat(firstEntered.await(3, TimeUnit.SECONDS)).isTrue();

            Future<ApiResult<AgentController.AgentAnswer>> second =
                    pool.submit(() -> controller.agent("b", "session-1"));
            // 给第二个请求时间走到 serializer 的锁上；它必须被第一个请求挡住。
            Thread.sleep(200);
            assertThat(maxInFlight.get()).isEqualTo(1);

            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).getCode()).isEqualTo(200);
            assertThat(second.get(5, TimeUnit.SECONDS).getCode()).isEqualTo(200);
            assertThat(maxInFlight.get()).isEqualTo(1);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void oneShotSessionIsEvictedAfterTheTurn() {
        AgentChatService chat = mock(AgentChatService.class);
        when(chat.ask(anyString(), isNull(), anyString()))
                .thenReturn(new AgentChatService.Answer("ok", List.of(), 0));

        AgentController controller = new AgentController(chat, new SessionSerializer());
        ApiResult<AgentController.AgentAnswer> result = controller.agent("hi", null);

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData().sessionId()).startsWith("one-shot:");
        verify(chat).evict(result.getData().sessionId());
    }
}
