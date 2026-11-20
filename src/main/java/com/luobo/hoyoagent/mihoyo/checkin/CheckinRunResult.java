package com.luobo.hoyoagent.mihoyo.checkin;

import com.luobo.hoyoagent.mihoyo.error.ErrorKind;

import java.util.List;

/**
 * 一轮签到（一个账号）的执行结果。
 *
 * <p>和 {@link CheckinRecord} 的区别：这是"刚跑完、还在内存里"的结果；Record 是落盘快照，
 * 多了一个 userId / 时间戳 / trigger。和 {@link CheckinOverview} 的区别：Overview 是"现在是什么状态"，
 * 只读、不触发任何签到请求。
 *
 * <p>2026-11-20 从 {@code CheckinRunResult} 改名并提取：三个 checkin 类型（run / record / view）
 * 以前没有命名规则，光看名字猜不出谁是谁。
 *
 * @param kind 整轮最需要处理的失败类别；全部成功时为 {@code null}
 */
public record CheckinRunResult(List<CheckinRoleResult> results, String message, ErrorKind kind) {

    public CheckinRunResult {
        results = results == null ? List.of() : List.copyOf(results);
    }

    public CheckinRunResult(List<CheckinRoleResult> results, String message) {
        this(results, message, null);
    }
}
