package com.luobo.hoyoagent.mihoyo.checkin;

import com.luobo.hoyoagent.mihoyo.error.ErrorKind;

/**
 * 单个角色这一轮的结果。
 *
 * <p>status: SIGNED（今天新签）/ ALREADY / FIRST_BIND / CAPTCHA / FAILED。
 *
 * <p>{@code kind} 是失败类别（AUTH / RISK / NEED_VERIFY…），成功时为 {@code null}。
 * 有了它，调度器不用去正则匹配中文文案就能判断"该不该提示重新扫码"。
 *
 * <p>2026-11-20 从 {@code CheckinService.RoleResult} 提出来：{@link CheckinRecord} 要把它落盘，
 * 让"存储格式"依赖"业务流程的嵌套类型"是把依赖方向搞反了。
 */
public record CheckinRoleResult(String gameBiz,
                                String gameName,
                                String uid,
                                String region,
                                String status,
                                String message,
                                int totalDays,
                                String reward,
                                ErrorKind kind) {

    /** 这一个角色今天算过关了吗（新签或本来签过）。 */
    public boolean ok() {
        return "SIGNED".equals(status) || "ALREADY".equals(status);
    }
}
