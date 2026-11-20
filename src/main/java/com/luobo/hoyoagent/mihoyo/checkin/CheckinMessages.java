package com.luobo.hoyoagent.mihoyo.checkin;

import com.luobo.hoyoagent.agent.notify.Notification;

/** 签到结果 → 通知内容。纯函数，不依赖任何传输实现。 */
public final class CheckinMessages {

    public static Notification of(CheckinRecord record) {
        return new Notification(title(record), text(record), level(record));
    }

    static String title(CheckinRecord record) {
        return switch (record.outcome()) {
            case DONE -> "米游社签到·完成 " + record.date();
            case PARTIAL -> "米游社签到·部分失败 " + record.date();
            case FAILED -> "米游社签到·失败 " + record.date();
            case SKIPPED -> "米游社签到·未执行 " + record.date();
        };
    }

    static String text(CheckinRecord record) {
        StringBuilder text = new StringBuilder(record.message());
        if (record.kind() != null) {
            text.append("（").append(record.kind()).append("）");
        }
        text.append('\n');
        for (CheckinRoleResult role : record.roles()) {
            text.append(role.gameName()).append(' ').append(role.uid()).append("：").append(role.message());
            if (role.totalDays() > 0) {
                text.append(" · 累计 ").append(role.totalDays()).append(" 天");
            }
            if (!role.ok() && role.kind() != null) {
                text.append("（").append(role.kind()).append("）");
            }
            text.append('\n');
        }
        return text.toString().trim();
    }

    static Notification.Level level(CheckinRecord record) {
        return switch (record.outcome()) {
            case DONE, SKIPPED -> Notification.Level.INFO;
            case PARTIAL -> Notification.Level.WARN;
            case FAILED -> Notification.Level.ERROR;
        };
    }

    private CheckinMessages() {
    }
}