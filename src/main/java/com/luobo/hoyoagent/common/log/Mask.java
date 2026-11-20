package com.luobo.hoyoagent.common.log;

/**
 * 日志脱敏的唯一出口。
 *
 * <p>以前每个类各写一份 {@code mask()}：6 个"前 4 后 4"、1 个"只留前 6"、1 个"只留后 4"、
 * 1 个"留前 12"，规则不一致就等于"总有几处会漏"。这里按用途固定三种：
 *
 * <ul>
 *   <li>{@link #id}：前 4 + 后 4，长度 ≤ 8 整串隐掉——既能看出"是不是同一个人"，又拼不出原值；</li>
 *   <li>{@link #tail}：只留后 N 位——uid / mid 这类末尾才有区分度的短标识；</li>
 *   <li>{@link #prefix}：只留前 N 位——URL / ticket 这类前缀就能说明"是哪个服务"。</li>
 * </ul>
 *
 * <p>约定：<b>日志里出现的用户 id、openid、sessionId 一律过这里</b>，不要在调用点手写 substring。
 */
public final class Mask {

    private static final String HIDDEN = "***";

    private Mask() {
    }

    /** 用户 id / openid / sessionId：前 4 + 后 4。 */
    public static String id(String value) {
        if (value == null || value.isBlank()) {
            return HIDDEN;
        }
        return value.length() <= 8
                ? HIDDEN
                : value.substring(0, 4) + HIDDEN + value.substring(value.length() - 4);
    }

    /** 只留后 {@code keep} 位。 */
    public static String tail(String value, int keep) {
        if (value == null || value.isBlank()) {
            return HIDDEN;
        }
        int n = Math.max(1, keep);
        return value.length() <= n ? HIDDEN : HIDDEN + value.substring(value.length() - n);
    }

    /** 只留前 {@code keep} 位。 */
    public static String prefix(String value, int keep) {
        if (value == null || value.isBlank()) {
            return HIDDEN;
        }
        int n = Math.max(1, keep);
        return value.length() <= n ? HIDDEN : value.substring(0, n) + "…";
    }
}
