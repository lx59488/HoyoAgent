package com.luobo.hoyoagent.channel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SessionSerializer} 的两条不变量：同会话不并发、不同会话不互斥。
 *
 * <p>类级 {@code @Timeout} 是刻意的：锁泄漏的表现是<b>测试挂住</b>而不是失败，
 * 没有超时的话 CI 会一直转，看不出是哪条用例坏了。
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class SessionSerializerTest {

    private final SessionSerializer serializer = new SessionSerializer();

    @Test
    void sameSessionNeverRunsTwoActionsAtOnce() throws Exception {
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);

        Runnable task = () -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            serializer.run("qqbot:c2c:AAA", () -> {
                peak.accumulateAndGet(inside.incrementAndGet(), Math::max);
                sleep(100);
                inside.decrementAndGet();
            });
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.submit(task);
            pool.submit(task);
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(8, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(peak.get()).isEqualTo(1);
    }

    @Test
    void differentSessionsRunInParallel() throws Exception {
        // 2 条带：hashCode("a")=97 落 1 号、hashCode("b")=98 落 0 号，确定不同条带
        SessionSerializer twoStripes = new SessionSerializer(2);
        CountDownLatch bothInside = new CountDownLatch(2);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> a = pool.submit(() -> twoStripes.call("a", () -> enter(bothInside)));
            Future<Boolean> b = pool.submit(() -> twoStripes.call("b", () -> enter(bothInside)));

            // 若被错误地串行化，第一个会等到 latch 超时 → false
            assertThat(a.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(b.get(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void exceptionStillReleasesTheLock() {
        assertThatThrownBy(() -> serializer.call("k", () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        // 锁泄漏的话这一行会一直卡住，由类级 @Timeout 兜底
        assertThat(serializer.call("k", () -> "after")).isEqualTo("after");
    }

    @Test
    void nullSessionIsTolerated() {
        assertThat(serializer.call(null, () -> "ok")).isEqualTo("ok");
    }

    /** 先报到，再等另一个也进来：两个都进来才算「真的并行」。 */
    private static boolean enter(CountDownLatch latch) {
        latch.countDown();
        try {
            return latch.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}