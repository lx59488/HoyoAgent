package com.luobo.hoyoagent.adapter.qq;

import com.luobo.hoyoagent.agent.AgentChatService;
import com.luobo.hoyoagent.channel.TextChunker;
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

    /** 一条消息一个虚拟线程：Agent 一轮 5~20 秒，不能堵住 WS 读循环。 */
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    /** 平台重连会重投事件，按 msg_id 去重。 */
    private final Map<String, Boolean> seen = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > 500;
        }
    };

    public QqBotDispatcher(QqBotProperties props, QqBotApi api, AgentChatService chat) {
        this.props = props;
        this.api = api;
        this.chat = chat;
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
            // 第一次跑一定会走到这里——日志里的 openid 就是要填进 QQ_ALLOWLIST 的值
            log.info("[QQ] 忽略：openid={} 不在白名单", mask(openid));
            return;
        }
        if (!markSeen(msgId)) {
            log.info("[QQ] 忽略：重复事件 msgId={}", mask(msgId));
            return;
        }

        String sessionId = "qqbot:c2c:" + openid;
        log.info("[QQ] ← {} [{}] {}", mask(openid), sessionId, abbreviate(text));

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
                if (api.sendC2c(openid, chunks.get(i), msgId, i + 1)) {   // msg_seq 从 1 开始
                    sent++;
                }
            } catch (RuntimeException e) {
                // 单条失败不拖累后面几条：网络抖动时用户至少能收到前半段
                log.warn("[QQ] 第 {} 条发送失败：{}", i + 1, e.toString());
            }
        }
        log.info("[QQ] → {} 回复 {} 字，{} 条（成功 {}）", mask(openid), reply.length(), chunks.size(), sent);
    }

    private synchronized boolean markSeen(String msgId) {
        return seen.put(msgId, Boolean.TRUE) == null;
    }

    private static String mask(String id) {
        return id.length() <= 8 ? "***" : id.substring(0, 4) + "***" + id.substring(id.length() - 4);
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