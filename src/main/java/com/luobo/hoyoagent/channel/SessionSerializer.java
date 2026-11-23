package com.luobo.hoyoagent.channel;

import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 同一会话串行执行，不同会话并行执行。
 *
 * <p>为什么需要它：一轮 Agent 5~20 秒，用户连发两条消息就会出现两个虚拟线程同时跑同一会话。
 * 而 {@code TurnWindowChatMemory.add()} 是「读 → 加一条 → 写回」（{@code getMessages} 之后
 * {@code updateMessages}），中间没有锁——两个线程各读到同一份 20 条、各自 +1 写回，
 * <b>后写的覆盖先写的</b>。不报错，只是模型无故失忆一格。
 *
 * <p>为什么用固定条带而不是「每个会话一把锁」：按 id 维护的 map 需要一个淘汰策略
 * （谁删、满了淘汰谁、删的同时有人正在用怎么办），而条带数量是常数，永远不会涨。
 * 代价是不同会话可能落进同一条带、短暂排队——自用规模下没有区别。
 *
 * <p>刻意不做的事：
 * <ul>
 *   <li><b>不做超时</b>：排队等多久由平台的被动回复窗口决定，这里加超时只是把问题挪个地方；</li>
 *   <li><b>不做公平锁</b>：同一会话连发三条，谁先拿到锁不影响结果（每轮都会看到前一轮）；</li>
 *   <li><b>不重入保护</b>：{@link ReentrantLock} 本身可重入，但不要在锁内再调一次 {@code call} 同一个 key——那会掩盖真正的递归调用。</li>
 * </ul>
 */
@Component
public class SessionSerializer {

    /**
     * 条带数。取 2 的幂只是习惯，实现用的是 {@link Math#floorMod}，取多少都正确。
     * 64 意味着「最多 64 个会话同时跑」；个人自用场景下这个上限碰不到。
     */
    private static final int STRIPES = 64;

    private final ReentrantLock[] locks;

    public SessionSerializer() {
        this(STRIPES);
    }

    /** 单测用：条带数可控，才能验「不同会话并行」。 */
    SessionSerializer(int stripes) {
        if (stripes < 1) {
            throw new IllegalArgumentException("stripes must be >= 1: " + stripes);
        }
        this.locks = new ReentrantLock[stripes];
        for (int i = 0; i < stripes; i++) {
            this.locks[i] = new ReentrantLock();
        }
    }

    /**
     * 在 {@code sessionId} 对应的条带上串行执行并返回结果。
     *
     * <p>异常照常抛给调用方，锁一定在 {@code finally} 里释放——否则一次失败就会把这个会话永久卡死。
     *
     * @param sessionId 会话标识；{@code null} 归到 0 号条带（不抛异常：null 是「没有会话」，不是编程错误）
     * @param action    要执行的整段逻辑
     */
    public <T> T call(String sessionId, Supplier<T> action) {
        ReentrantLock lock = lockOf(sessionId);
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /** 不需要返回值时的便捷入口。 */
    public void run(String sessionId, Runnable action) {
        call(sessionId, () -> {
            action.run();
            return null;
        });
    }

    private ReentrantLock lockOf(String sessionId) {
        // 用 floorMod 而不是 %：String.hashCode() 可能是负数，负数下标会直接 ArrayIndexOutOfBoundsException
        int index = sessionId == null ? 0 : Math.floorMod(sessionId.hashCode(), locks.length);
        return locks[index];
    }
}
