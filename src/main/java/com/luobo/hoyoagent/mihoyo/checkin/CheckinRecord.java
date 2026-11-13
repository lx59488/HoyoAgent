package com.luobo.hoyoagent.mihoyo.checkin;

import com.luobo.hoyoagent.mihoyo.error.ErrorKind;

import java.util.List;

/**
 * 一次签到运行的结果快照，落盘在 {@code ~/.hoyo/checkin/YYYY-MM-DD.json}。
 *
 * <p>为什么字段全是字符串/枚举而不是 {@code LocalDate} / {@code Instant}：
 * 这个文件要能被旧版本、新版本、以及"人直接打开看"三方同时消费。
 * 时间统一存 ISO-8601 字符串（带时区偏移），排序直接按字符串比，
 * 不依赖任何 Jackson 的 java-time 模块配置。
 *
 * <p>{@code userId} 也写进文件：单用户阶段文件名只有日期，万一以后目录被多个账号共用，
 * 调度器靠这个字段拒绝把别人的记录当成自己的（见 {@code CheckinScheduler}）。
 */
public record CheckinRecord(String userId,
                            String date,
                            Trigger trigger,
                            Outcome outcome,
                            ErrorKind kind,
                            String startedAt,
                            String finishedAt,
                            String message,
                            List<CheckinService.RoleResult> roles) {

    public CheckinRecord {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

    /** 今天的任务算完成了吗（新签 + 本来签过都算）。 */
    public boolean done() {
        return outcome == Outcome.DONE;
    }

    /** 是不是"该有人看一眼"的状态：没签上、只签了一部分、或者根本没有可签角色。 */
    public boolean attention() {
        return outcome == Outcome.PARTIAL || outcome == Outcome.FAILED;
    }

    public enum Trigger {
        /** 每天 00:10 的 cron */
        SCHEDULED,
        /** 启动后补签（应用错过 00:10 时） */
        STARTUP,
        /** 人主动触发（调试页 / curl） */
        MANUAL
    }

    public enum Outcome {
        /** 全部角色 SIGNED 或 ALREADY */
        DONE,
        /** 有成功也有失败 */
        PARTIAL,
        /** 一个都没成（含"读不到角色"） */
        FAILED,
        /** 没跑：未绑定账号、功能关闭等 */
        SKIPPED
    }
}
