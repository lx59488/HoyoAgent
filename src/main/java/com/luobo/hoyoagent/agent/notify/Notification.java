package com.luobo.hoyoagent.agent.notify;

public record Notification(String title, String text, Level level) {

    public Notification {
        title = title == null ? "" : title;
        text = text == null ? "" : text;
        level = level == null ? Level.INFO : level;
    }

    public enum Level {
        /** 正常结果，知道一下 */
        INFO,
        /** 需要人看一眼 */
        WARN,
        /** 必须处理 */
        ERROR
    }
}
