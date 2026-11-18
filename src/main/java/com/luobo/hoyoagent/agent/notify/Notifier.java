package com.luobo.hoyoagent.agent.notify;
/**
 * 出站通知通道：只负责把一条 Notification 送到某个地方。
 *
 * <p>签名里不出现 CheckinRecord / "签到"这类业务名词。业务语义属于生产者一侧
 * （例如 CheckinMessages 负责把签到记录翻译成 Notification）。
 * 边界是否画对，用这个测：明天加「cookie 失效提醒」，这个接口要改吗？
 */
public interface Notifier {

    void send(Notification notification);

    Notifier NOOP = notification -> { };
}
