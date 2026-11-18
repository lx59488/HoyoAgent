package com.luobo.hoyoagent.adapter.qq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class QqBotGateway {

    private static final Logger log = LoggerFactory.getLogger(QqBotGateway.class);

    /** 1 << 25 = GROUP_AND_C2C_EVENT：私聊 + 群里 @ 机器人。 */
    private static final int INTENTS = 1 << 25;

    private final QqBotProperties props;
    private final QqBotApi api;
    private final QqBotDispatcher dispatcher;
    private final HttpClient http;
    private final ObjectMapper mapper;

    private final AtomicInteger seq = new AtomicInteger();
    private final Object writeLock = new Object();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "qq-heartbeat");
        t.setDaemon(true);
        return t;
    });

    private volatile ScheduledFuture<?> heartbeat;

    public QqBotGateway(QqBotProperties props, QqBotApi api, QqBotDispatcher dispatcher,
                        @Qualifier("qqHttpClient") HttpClient http, ObjectMapper mapper) {
        this.props = props;
        this.api = api;
        this.dispatcher = dispatcher;
        this.http = http;
        this.mapper = mapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!props.enabled()) {
            log.info("[QQ] 未启用（qq.enabled=false）");
            return;
        }
        if (props.appId().isBlank() || props.clientSecret().isBlank()) {
            log.warn("[QQ] 缺 QQ_APP_ID / QQ_APP_SECRET，跳过启动");
            return;
        }
        if (props.allowlist().isEmpty()) {
            log.warn("[QQ] QQ_ALLOWLIST 为空：所有消息都会被忽略（fail closed）");
        }
        Thread.ofVirtual().name("qq-gateway").start(this::loop);
    }

    private void loop() {
        long backoff = 2_000;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                connectOnce();
                backoff = 2_000;
            } catch (Exception e) {
                log.warn("[QQ] 网关断开：{}", e.toString());
            }
            cancelHeartbeat();
            sleep(backoff);
            backoff = Math.min(backoff * 2, 60_000);
        }
    }

    private void connectOnce() throws Exception {
        String url = gatewayUrl();
        Listener listener = new Listener();
        http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(url), listener)
                .get(15, TimeUnit.SECONDS);
        log.info("[QQ] 网关已连接 {}", url);
        listener.closed.await();
    }

    private String gatewayUrl() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(props.apiBase() + "/gateway"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "QQBot " + api.accessToken())
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("取网关地址失败 HTTP " + resp.statusCode() + "：" + resp.body());
        }
        String url = mapper.readTree(resp.body()).path("url").asString("");
        if (url.isBlank()) {
            throw new IllegalStateException("网关地址为空：" + resp.body());
        }
        return url;
    }

    private void identify(WebSocket socket) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("token", "QQBot " + api.accessToken());
        d.put("intents", INTENTS);
        d.put("shard", List.of(0, 1));
        d.put("properties", Map.of("$os", "windows", "$browser", "hoyoagent", "$device", "hoyoagent"));
        send(socket, "{\"op\":2,\"d\":" + mapper.writeValueAsString(d) + "}");
    }

    private void startHeartbeat(WebSocket socket, long intervalMs) {
        cancelHeartbeat();
        heartbeat = timer.scheduleAtFixedRate(() -> {
            try {
                int last = seq.get();
                send(socket, "{\"op\":1,\"d\":" + (last <= 0 ? "null" : String.valueOf(last)) + "}");
            } catch (Exception e) {
                log.debug("[QQ] 心跳失败：{}", e.toString());
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    private void cancelHeartbeat() {
        ScheduledFuture<?> hb = heartbeat;
        if (hb != null) {
            hb.cancel(false);
            heartbeat = null;
        }
    }

    private void send(WebSocket socket, String json) {
        if (socket == null) {
            throw new IllegalStateException("网关未连接");
        }
        synchronized (writeLock) {          // JDK WebSocket 不允许并发写
            socket.sendText(json, true).join();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private final class Listener implements WebSocket.Listener {
        final CountDownLatch closed = new CountDownLatch(1);
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket socket) {
            socket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String frame = buffer.toString();
                buffer.setLength(0);
                try {
                    onFrame(socket, frame);          // ← 这里
                } catch (Exception e) {
                    log.warn("[QQ] 帧处理失败：{}", e.toString());
                }
            }
            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int code, String reason) {
            log.info("[QQ] 网关关闭 code={} reason={}", code, reason);
            closed.countDown();
            return null;
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            log.warn("[QQ] 网关错误：{}", error.toString());
            closed.countDown();
        }

        private void onFrame(WebSocket socket, String frame) {
            JsonNode node = mapper.readTree(frame);
            int op = node.path("op").asInt(-1);
            switch (op) {
                case 10 -> {
                    long interval = node.path("d").path("heartbeat_interval").asLong(41_250);
                    identify(socket);                    // ←
                    startHeartbeat(socket, interval);    // ←
                }
                case 11 -> { }
                case 0 -> {
                    int s = node.path("s").asInt(0);
                    if (s > 0) {
                        seq.set(s);                         // 心跳要回传最后一个 seq
                    }
                    String type = node.path("t").asString("");
                    if ("READY".equals(type)) {
                        log.info("[QQ] READY，网关就绪（intents={}）", INTENTS);
                    } else {
                        dispatcher.onEvent(type, node.path("d"));
                    }
                }
                case 7 -> {
                    log.info("[QQ] 平台要求重连（op7）");
                    closeQuietly(socket);                // ←
                }
                case 9 -> {
                    log.warn("[QQ] 会话失效（op9），重连");
                    closeQuietly(socket);                // ←
                }
                default -> log.debug("[QQ] 忽略 op={}", op);
            }
        }

        private void closeQuietly(WebSocket socket) {
            try {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "reconnect").join();
            } catch (Exception ignored) {
                // 已经断了就直接往下走
            } finally {
                closed.countDown();
            }
        }
    }
}