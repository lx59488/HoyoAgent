package com.luobo.hoyoagent.adapter.qq;

import com.luobo.hoyoagent.common.log.Mask;

import com.luobo.hoyoagent.agent.AgentChatService;
import com.luobo.hoyoagent.channel.SessionRegistry;
import com.luobo.hoyoagent.channel.TextChunker;
import com.luobo.hoyoagent.common.exception.BusinessException;
import com.luobo.hoyoagent.mihoyo.auth.QrLoginService;
import com.luobo.hoyoagent.mihoyo.binding.BindingService;
import com.luobo.hoyoagent.mihoyo.credential.AccountService;
import com.luobo.hoyoagent.mihoyo.credential.Credential;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class QqBotDispatcher {

    private static final Logger log = LoggerFactory.getLogger(QqBotDispatcher.class);

    private final QqBotProperties props;
    private final QqBotApi api;
    private final AgentChatService chat;
    private final SessionRegistry sessions;
    private final BindingService binding;

    private final AccountService accounts;

    /** 一条消息一个虚拟线程：Agent 一轮 5~20 秒，不能堵住 WS 读循环。 */
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    /** 平台重连会重投事件，按 msg_id 去重。 */
    private final Map<String, Boolean> seen = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > 500;
        }
    };

    public QqBotDispatcher(QqBotProperties props, QqBotApi api, AgentChatService chat,
                           SessionRegistry sessions, BindingService binding, AccountService accounts) {
        this.props = props;
        this.api = api;
        this.chat = chat;
        this.sessions = sessions;
        this.binding = binding;
        this.accounts = accounts;
    }

    public void onEvent(String type, JsonNode d) {
        // 群聊事件（GROUP_AT_MESSAGE_CREATE）暂不接：白名单只挡"谁能触发"，挡不住"谁能看见"，
        // 见 docs/qq-inbound.md §4
        if (!"C2C_MESSAGE_CREATE".equals(type)) {
            log.debug("[QQ] 忽略事件 t={}", type);
            return;
        }
        workers.execute(() -> handle(d));
    }

    private void handle(JsonNode d) {
        String openid = d.path("author").path("user_openid").asString("");
        if (openid.isBlank()) {
            openid = d.path("author").path("id").asString("");
        }
        String msgId = d.path("id").asString("");
        String text = d.path("content").asString("").trim();

        if (openid.isBlank() || msgId.isBlank() || text.isBlank()) {
            log.info("[QQ] 忽略：字段不全，d={}", abbreviate(d.toString()));
            return;
        }
        if (!props.allowlist().contains(openid)) {
            // 第一次跑一定会走到这里，而这一行是"该往 QQ_ALLOWLIST 里填什么"的唯一来源，
            // 所以**故意不掩码**：掩掉之后人就没法照着抄，机器人永远配不通。
            // openid 不是凭据（没有 AppSecret 拿它发不出消息），泄露风险远小于"配不通"。
            log.info("[QQ] 不在白名单：openid={}（把整串值填进 QQ_ALLOWLIST{}）",
                    openid, props.allowlist().isEmpty() ? "；当前白名单为空" : "");
            return;
        }
        if (!markSeen(msgId)) {
            log.info("[QQ] 忽略：重复事件 msgId={}", Mask.id(msgId));
            return;
        }

        String userId = "qq:" + openid;
        String sessionId = "qqbot:c2c:" + openid;
        sessions.register(sessionId, userId);      // 每条消息都注册：重启后第一条就恢复映射
        log.info("[QQ] ← {} [{}] {}", Mask.id(openid), sessionId, abbreviate(text));
        // 绑定是确定性动作：命中关键词就直接走流程，不进模型
        BindingService.Intent intent = binding.intentOf(text);
        if (intent != BindingService.Intent.NONE) {
            handleBinding(openid, userId, intent, msgId);
            return;
        }
            String reply;
        try {
            reply = chat.ask(sessionId, text).reply();
        } catch (Exception e) {
            log.warn("[QQ] Agent 调用失败：{}", e.toString());
            reply = "【出错了】" + describe(e);
        }
        if (reply == null || reply.isBlank()) {
            reply = "（模型没有返回内容）";
        }

        List<String> chunks = TextChunker.split(reply, props.replyChunkChars(), props.maxReplies());
        int sent = 0;
        for (int i = 0; i < chunks.size(); i++) {
            try {
                if (api.sendC2C(openid, chunks.get(i), msgId, i + 1)) {   // msg_seq 从 1 开始
                    sent++;
                }
            } catch (RuntimeException e) {
                // 单条失败不拖累后面几条：网络抖动时用户至少能收到前半段
                log.warn("[QQ] 第 {} 条发送失败：{}", i + 1, e.toString());
            }
        }
        log.info("[QQ] → {} 回复 {} 字，{} 条（成功 {}）", Mask.id(openid), reply.length(), chunks.size(), sent);
    }

    /** 绑定/换绑/解绑：三条确定性路径，最多用 3 个 msg_seq（图片 1、引导 2、回执 3）。 */
    private void handleBinding(String openid, String userId, BindingService.Intent intent, String msgId) {
        if (intent == BindingService.Intent.UNBIND) {
            boolean removed = binding.unbind(userId);
            api.sendC2C(openid, removed ? "已解绑，本机保存的凭据也删掉了。" : "你还没有绑定米游社账号。",
                    msgId, 1);
            return;
        }

        if (intent == BindingService.Intent.START && accounts.isBound(userId)) {
            // 覆盖是不可逆的：先问一句，别让"绑定"两个字把现有账号冲掉
            String uid = accounts.credentialOf(userId).map(Credential::uid).orElse("");
            api.sendC2C(openid, "当前已绑定米游社 uid " + Mask.tail(uid, 4)
                    + "，继续会覆盖。回复「确认换绑」再发新二维码。", msgId, 1);
            return;
        }

        BindingService.QrTicket qr;
        try {
            qr = binding.start(userId);
        } catch (BusinessException e) {
            api.sendC2C(openid, e.getMessage(), msgId, 1);
            return;
        }

        if (!api.sendC2CImage(openid, qr.png(), msgId, 1)) {
            api.sendC2C(openid, "二维码发送失败，稍后再试。", msgId, 2);
            return;
        }
        api.sendC2C(openid, "用米游社 App 扫这张图，" + (qr.expiresInSeconds() / 60) + " 分钟内有效。",
                msgId, 2);

        if (!binding.acquirePoller(qr.ticket())) {
            log.info("[QQ] 这张码已经有轮询线程，跳过");
            return;
        }
        Thread.ofVirtual().name("qq-bind-" + Mask.id(openid))
                .start(() -> awaitBind(openid, userId, qr.ticket(), msgId));
    }

    /**
     * 后台等扫码结果。用<b>同一条 msg_id</b> 的 seq=3 回执——QQ 不允许主动推送，
     * 所以"绑定成功"必须在用户那条消息的被动回复窗口里送出去。
     */
    private void awaitBind(String openid, String userId, String ticket, String msgId) {
        long deadline = System.currentTimeMillis() + 170_000L;   // 略小于二维码的 3 分钟
        String last = "";
        try {
            while (System.currentTimeMillis() < deadline) {
                sleep(2500);
                QrLoginService.Status status;
                try {
                    status = binding.poll(userId, ticket);
                } catch (RuntimeException e) {
                    log.warn("[QQ] 绑定轮询失败：{}", e.toString());
                    continue;
                }
                if (status.state().equals(last)) {
                    continue;
                }
                last = status.state();
                log.info("[QQ] 绑定状态 {} → {}（user={}）", Mask.id(openid),
                        status.state(), Mask.id(userId));

                String reply = switch (status.state()) {
                    case "BOUND" -> "绑定成功：米游社 uid " + Mask.tail(status.uid(), 4);
                    case "EXPIRED" -> "二维码过期了，回复「绑定」重新生成。";
                    case "FAILED" -> "绑定失败：" + status.message();
                    default -> "";
                };
                if (!reply.isEmpty()) {
                    api.sendC2C(openid, reply, msgId, 3);
                    return;
                }
            }
        } finally {
            binding.releasePoller(ticket);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private synchronized boolean markSeen(String msgId) {
        return seen.put(msgId, Boolean.TRUE) == null;
    }

    private static String abbreviate(String text) {
        String one = text.replace('\n', ' ');
        return one.length() <= 40 ? one : one.substring(0, 40) + "…";
    }

    private static String describe(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}