/*
 * scripts/qq-bridge/QQBridge.java —— P10 Step 0：把 QQ 私聊接到 GET /agent
 *
 * 定位：一次性验证脚本，不是产品代码。跑通就证明「QQ 里和 Agent 对话」成立，
 *       正式的适配器在 adapter/onebot（见 docs/qq-inbound.md）。
 *
 * 用法（在仓库根目录，需要服务已启动）：
 *     java scripts/qq-bridge/QQBridge.java
 *     java scripts/qq-bridge/QQBridge.java --selftest    # 离线自检：不连协议端、不连服务
 *
 * 数据流：
 *     QQ 用户 → LLBot（协议端）→ OneBot v11 正向 WS → 本脚本 → GET /agent → 本脚本 → send_private_msg
 *
 * 三个刻意的决定：
 * 1. 零依赖：JDK 自带的 java.net.http.WebSocket + 一个 80 行的 JSON 解析器，
 *    不引 Maven、不装 npm 包，删掉这一个文件就干净了。
 * 2. 每条消息一个虚拟线程：Agent 一轮 5~20 秒，绝不能堵住 WS 读循环；
 *    而 ToolCallRecorder 是 ThreadLocal，共用线程会把 toolsCalled 串到一起。
 * 3. 默认只在私聊里回白名单：现在整个进程只有一份米游社凭据（CredentialProvider 单例），
 *    进群 = 群里任何人看到你的账号数据。群聊必须显式打开。
 */

import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class QQBridge {

    /** 一次回复最多拆几条，防止模型长篇大论把 QQ 刷屏。 */
    private static final int MAX_CHUNKS = 8;

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static void log(String fmt, Object... args) {
        System.out.println("[" + LocalTime.now().format(CLOCK) + "] " + String.format(fmt, args));
    }

    private static void warn(String fmt, Object... args) {
        System.err.println("[" + LocalTime.now().format(CLOCK) + "] WARN  " + String.format(fmt, args));
    }

    private static void error(String fmt, Object... args) {
        System.err.println("[" + LocalTime.now().format(CLOCK) + "] ERROR " + String.format(fmt, args));
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--selftest".equals(args[0])) {
            Selftest.run();
            return;
        }
        if (args.length > 0 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
            System.out.println("""
                    用法: java scripts/qq-bridge/QQBridge.java [--selftest]

                    配置（环境变量优先，其次仓库根目录 .env）：
                      QQ_ONEBOT_WS_URL      协议端正向 WebSocket，默认 ws://127.0.0.1:3001（LLBot / NapCat 都是这个口）
                      QQ_ONEBOT_TOKEN       协议端正向 WS 的 token，默认空
                      QQ_AGENT_BASE_URL     HoyoAgent 地址，默认 http://127.0.0.1:8080
                      QQ_ALLOWLIST          逗号分隔的 QQ 号白名单；为空 = 谁都不回（fail closed）
                      QQ_GROUP_MODE         off（默认）/ allow：群聊要不要回
                      QQ_REPLY_CHUNK_CHARS  单条消息最大字数，默认 800
                      QQ_TYPING_HINT        true（默认）/ false：超过 2.5 秒仍未答时先回一句"查一下"
                    """);
            return;
        }

        Config cfg = Config.load();
        log("配置：ws=%s agent=%s 白名单=%s 群聊=%s",
                cfg.wsUrl(), cfg.agentBase(),
                cfg.allowlist().isEmpty() ? "(空)" : cfg.allowlist().toString(),
                cfg.groupMode() ? "allow" : "off");
        if (cfg.allowlist().isEmpty()) {
            warn("QQ_ALLOWLIST 为空：所有消息都会被忽略（fail closed）。先填你自己的 QQ 号。");
        }
        if (cfg.groupMode()) {
            warn("QQ_GROUP_MODE=allow：群里任何人都能看到机器人的回复，即使是你的账号数据。");
        }

        long backoff = 3_000;
        while (true) {
            try {
                new Session(cfg).run();
                backoff = 3_000;
            } catch (Exception e) {
                error("连接断开：%s", e);
            }
            log("%d ms 后重连…", backoff);
            Thread.sleep(backoff);
            backoff = Math.min(backoff * 2, 30_000);
        }
    }

    // ── 一条连接 ────────────────────────────────────────────────────────────

    static final class Session implements WebSocket.Listener {

        private final Config cfg;

        private final HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        /** 每条消息一个虚拟线程：读循环只负责分发。 */
        private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

        private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "qq-hint");
            t.setDaemon(true);
            return t;
        });

        /** 同步发送用：WebSocket 不允许两个线程同时写。 */
        private final Object writeLock = new Object();

        private final Map<String, CompletableFuture<Map<String, Object>>> pending = new ConcurrentHashMap<>();

        private final AtomicLong echo = new AtomicLong();

        private final Seen seen = new Seen();

        private final StringBuilder frame = new StringBuilder();

        private final CountDownLatch closed = new CountDownLatch(1);

        private volatile WebSocket ws;

        private volatile Throwable failure;

        Session(Config cfg) {
            this.cfg = cfg;
        }

        void run() throws Exception {
            WebSocket.Builder builder = http.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10));
            if (!cfg.token().isBlank()) {
                builder.header("Authorization", "Bearer " + cfg.token());
            }
            this.ws = builder.buildAsync(URI.create(cfg.wsUrl()), this).get(15, TimeUnit.SECONDS);
            log("已连上 %s，等待消息（Ctrl+C 退出）", cfg.wsUrl());
            closed.await();
            if (failure != null) {
                throw new IllegalStateException("连接异常", failure);
            }
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            frame.append(data);
            if (last) {
                String text = frame.toString();
                frame.setLength(0);
                try {
                    onFrame(text);
                } catch (Exception e) {
                    error("帧处理失败：%s", e);
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable err) {
            failure = err;
            closed.countDown();
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onClose(WebSocket webSocket, int code, String reason) {
            log("连接关闭 code=%d reason=%s", code, reason);
            closed.countDown();
            return null;
        }

        // ── 收 ──────────────────────────────────────────────────────────────

        private void onFrame(String text) {
            Object parsed = Json.parse(text);
            if (!(parsed instanceof Map<?, ?> raw)) {
                return;
            }
            Map<String, Object> m = Json.map(raw);

            // 带 echo 的是 action 的返回值，不是事件
            Object echoId = m.get("echo");
            if (echoId != null) {
                CompletableFuture<Map<String, Object>> waiting = pending.remove(String.valueOf(echoId));
                if (waiting != null) {
                    waiting.complete(m);
                }
                return;
            }

            // 只认消息事件：notice / request / meta_event 全部忽略
            if (!"message".equals(Json.str(m.get("post_type")))) {
                return;
            }
            workers.execute(() -> handle(m));
        }

        private void handle(Map<String, Object> m) {
            String type = Json.str(m.get("message_type"));
            long uid = Json.lng(m.get("user_id"), -1L);
            long gid = Json.lng(m.get("group_id"), 0L);
            Target target = new Target(type, uid, gid);

            try {
                if (!Gate.allowed(cfg, type, uid, gid)) {
                    log("忽略：%s user=%s group=%s（不在白名单，或群聊未开启）",
                            type, mask(uid), gid == 0 ? "-" : String.valueOf(gid));
                    return;
                }

                String text = Json.textOf(m.get("message"));
                if (text.isBlank()) {
                    log("忽略：user=%s 发的是非文本消息", mask(uid));
                    return;
                }

                String eventKey = Json.str(m.get("message_id")) + "|" + uid;
                if (!seen.add(eventKey)) {
                    log("忽略：重复事件 %s", eventKey);
                    return;
                }

                // 会话 id 由适配器决定，Agent 只当不透明字符串用（见 AgentController 的注释）。
                // 群聊按「群 + 人」分桶：用群号当 id 会让全群共享一段上下文。
                String sessionId = "group".equals(type)
                        ? "qq:group:" + gid + ":" + uid
                        : "qq:c2c:" + uid;

                log("← %s [%s] %s", mask(uid), sessionId, abbreviate(text));

                AtomicBoolean answered = new AtomicBoolean();
                if (cfg.typingHint()) {
                    timer.schedule(() -> {
                        if (!answered.get()) {
                            sendQuietly(target, "（收到，我查一下…）");
                        }
                    }, 2_500, TimeUnit.MILLISECONDS);
                }

                String reply;
                try {
                    reply = askAgent(text, sessionId);
                } finally {
                    answered.set(true);
                }

                if (reply == null || reply.isBlank()) {
                    reply = "（模型没有返回内容）";
                }
                List<String> chunks = Text.split(reply, cfg.chunkChars());
                if (chunks.size() > MAX_CHUNKS) {
                    chunks = new ArrayList<>(chunks.subList(0, MAX_CHUNKS));
                    chunks.add("（回复太长，先发这些；细节可以继续问）");
                }
                for (String chunk : chunks) {
                    send(target, chunk);
                }
                log("→ %s 回复 %d 字，%d 条", mask(uid), reply.length(), chunks.size());

            } catch (Exception e) {
                error("处理消息失败：%s", e);
                // 出错也要说话，否则用户那边就是石沉大海
                sendQuietly(target, "【出错了】" + describe(e));
            }
        }

        // ── 调 Agent ────────────────────────────────────────────────────────

        /**
         * 这里的每一种失败都要变成一句用户能看懂的话，不能直接抛。
         * 排障现场最常见的三个原因是：服务没起、端口不对、跑的是别的进程（返回 HTML）。
         */
        private String askAgent(String text, String sessionId) {
            String url = cfg.agentBase() + "/agent?q=" + URLEncoder.encode(text, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(120))
                    .header("X-Session-Id", sessionId)
                    .GET()
                    .build();

            HttpResponse<String> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (ConnectException e) {
                log("Agent 连不上：%s", cfg.agentBase());
                return "【连不上 Agent】确认 HoyoAgent 已在 " + cfg.agentBase() + " 启动";
            } catch (HttpTimeoutException e) {
                log("Agent 超时：单轮超过 120 秒");
                return "【Agent 超时】这一轮超过 120 秒，去看服务端日志";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "【被打断】";
            } catch (Exception e) {
                log("Agent 调用失败：%s", e.toString());
                return "【调用失败】" + describe(e);
            }

            if (response.statusCode() != 200) {
                log("Agent 返回 HTTP %d：%s", response.statusCode(), snippet(response.body()));
                return "【Agent 返回 HTTP " + response.statusCode() + "】" + snippet(response.body());
            }

            Map<String, Object> body;
            try {
                body = Json.map(Json.parse(response.body()));
            } catch (RuntimeException e) {
                log("Agent 返回的不是 JSON：%s", snippet(response.body()));
                return "【Agent 返回的不是 JSON】" + snippet(response.body());
            }

            long code = Json.lng(body.get("code"), -1L);
            if (code != 200) {
                String message = Json.str(body.get("message"));
                return "【服务出错】" + (message.isBlank() ? "code=" + code : message);
            }
            return Json.str(Json.map(body.get("data")).get("reply"));
        }

        // ── 发 ──────────────────────────────────────────────────────────────

        private void send(Target target, String text) {
            String action = target.isGroup() ? "send_group_msg" : "send_private_msg";
            Map<String, Object> params = new LinkedHashMap<>();
            if (target.isGroup()) {
                params.put("group_id", target.gid());
            } else {
                params.put("user_id", target.uid());
            }
            // 用消息段数组而不是 CQ 字符串：不用转义，模型回复里的 [ ] 不会被吞
            params.put("message", List.of(Map.of("type", "text", "data", Map.of("text", text))));

            Map<String, Object> response = call(action, params);
            long retcode = Json.lng(response.get("retcode"), -1L);
            if (retcode != 0) {
                warn("发送失败 retcode=%d %s", retcode, Json.str(response.get("message")));
            }
        }

        private void sendQuietly(Target target, String text) {
            try {
                send(target, text);
            } catch (Exception e) {
                warn("兜底消息没发出去：%s", e);
            }
        }

        private Map<String, Object> call(String action, Map<String, Object> params) {
            String id = "e" + echo.incrementAndGet();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("action", action);
            payload.put("params", params);
            payload.put("echo", id);

            CompletableFuture<Map<String, Object>> answer = new CompletableFuture<>();
            pending.put(id, answer);
            try {
                String json = Json.write(payload);
                synchronized (writeLock) {
                    ws.sendText(json, true).get(10, TimeUnit.SECONDS);
                }
                return answer.get(20, TimeUnit.SECONDS);
            } catch (Exception e) {
                pending.remove(id);
                warn("调用 %s 失败：%s", action, e);
                return Map.of();
            }
        }
    }

    record Target(String type, long uid, long gid) {
        boolean isGroup() {
            return "group".equals(type);
        }
    }

    // ── 白名单闸门 ──────────────────────────────────────────────────────────

    /**
     * 单用户阶段的唯一防线。
     *
     * <p>能改的只有「谁」能触发，改不了「用谁的账号」——凭据是进程级的，
     * 所以群聊模式仍然等于把你的数据播给全群。这在 P8 多账号做完之前无解。
     */
    static final class Gate {

        static boolean allowed(Config cfg, String type, long uid, long gid) {
            if (uid <= 0 || !cfg.allowlist().contains(uid)) {
                return false;
            }
            return !"group".equals(type) || cfg.groupMode();
        }
    }

    /** 事件去重：OneBot 断线重连或重复推送时，同一条消息会被投多次。 */
    static final class Seen {

        private final Map<String, Boolean> keys = new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > 500;
            }
        };

        synchronized boolean add(String key) {
            return keys.put(key, Boolean.TRUE) == null;
        }
    }

    // ── 回复切片 ────────────────────────────────────────────────────────────

    static final class Text {

        /** 按行切，单行超长再硬切；不丢字（换行分隔处除外）。 */
        static List<String> split(String text, int maxChars) {
            int max = maxChars < 50 ? 800 : maxChars;
            List<String> out = new ArrayList<>();
            StringBuilder current = new StringBuilder();

            for (String line : text.split("\n", -1)) {
                if (line.length() > max) {
                    if (current.length() > 0) {
                        out.add(current.toString());
                        current.setLength(0);
                    }
                    int i = 0;
                    while (line.length() - i > max) {
                        out.add(line.substring(i, i + max));
                        i += max;
                    }
                    current.append(line.substring(i));
                    continue;
                }
                if (current.length() + line.length() + 1 > max && current.length() > 0) {
                    out.add(current.toString());
                    current.setLength(0);
                }
                if (current.length() > 0) {
                    current.append('\n');
                }
                current.append(line);
            }
            if (current.length() > 0) {
                out.add(current.toString());
            }
            return out;
        }
    }

    // ── 配置 ────────────────────────────────────────────────────────────────

    record Config(String wsUrl, String token, String agentBase, Set<Long> allowlist,
                  boolean groupMode, int chunkChars, boolean typingHint) {

        static Config load() {
            Map<String, String> dotenv = dotenv();
            String ws = value(dotenv, "QQ_ONEBOT_WS_URL", "ws://127.0.0.1:3001");
            if (!ws.startsWith("ws://") && !ws.startsWith("wss://")) {
                throw new IllegalArgumentException("QQ_ONEBOT_WS_URL 必须是 ws:// 或 wss://，当前=" + ws);
            }
            return new Config(
                    ws,
                    value(dotenv, "QQ_ONEBOT_TOKEN", ""),
                    stripTrailingSlash(value(dotenv, "QQ_AGENT_BASE_URL", "http://127.0.0.1:8080")),
                    allowlist(value(dotenv, "QQ_ALLOWLIST", "")),
                    "allow".equalsIgnoreCase(value(dotenv, "QQ_GROUP_MODE", "off")),
                    (int) number(value(dotenv, "QQ_REPLY_CHUNK_CHARS", "800"), 800),
                    !"false".equalsIgnoreCase(value(dotenv, "QQ_TYPING_HINT", "true")));
        }

        /** 环境变量优先，其次 .env——跟 Spring 的 spring.config.import 保持同一份来源。 */
        private static Map<String, String> dotenv() {
            Map<String, String> map = new LinkedHashMap<>();
            for (String name : List.of(".env", "../.env")) {
                Path path = Path.of(name);
                if (!Files.isReadable(path)) {
                    continue;
                }
                try {
                    for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                        String trimmed = line.trim();
                        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                            continue;
                        }
                        int eq = trimmed.indexOf('=');
                        if (eq <= 0) {
                            continue;
                        }
                        String key = trimmed.substring(0, eq).trim();
                        String val = trimmed.substring(eq + 1).trim();
                        if (val.length() >= 2 && (val.startsWith("\"") && val.endsWith("\"")
                                || val.startsWith("'") && val.endsWith("'"))) {
                            val = val.substring(1, val.length() - 1);
                        }
                        map.putIfAbsent(key, val);
                    }
                    log("读到了 %s", path.toAbsolutePath());
                } catch (Exception e) {
                    warn("读 %s 失败：%s", path, e.toString());
                }
                break;
            }
            return map;
        }

        private static String value(Map<String, String> dotenv, String key, String fallback) {
            String env = System.getenv(key);
            if (env != null && !env.isBlank()) {
                return env.trim();
            }
            String fromFile = dotenv.get(key);
            return fromFile == null || fromFile.isBlank() ? fallback : fromFile.trim();
        }

        private static Set<Long> allowlist(String raw) {
            Set<Long> ids = new LinkedHashSet<>();
            for (String piece : raw.split("[,;\\s]+")) {
                String t = piece.trim();
                if (t.isEmpty()) {
                    continue;
                }
                try {
                    ids.add(Long.parseLong(t));
                } catch (NumberFormatException e) {
                    warn("QQ_ALLOWLIST 里的 %s 不是数字，已忽略", t);
                }
            }
            return ids;
        }

        private static long number(String raw, long fallback) {
            try {
                return Long.parseLong(raw.trim());
            } catch (RuntimeException e) {
                return fallback;
            }
        }

        private static String stripTrailingSlash(String url) {
            return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        }
    }

    private static String mask(long id) {
        String text = String.valueOf(id);
        return text.length() <= 4 ? "***" : "***" + text.substring(text.length() - 4);
    }

    private static String abbreviate(String text) {
        String oneLine = text.replace('\n', ' ');
        return oneLine.length() <= 60 ? oneLine : oneLine.substring(0, 60) + "…";
    }

    /** 异常 → 一句能贴进 QQ 的话。ConnectException 的 message 经常是 null，所以要看类型名。 */
    private static String describe(Throwable e) {
        Throwable root = e;
        while ((root instanceof java.util.concurrent.ExecutionException
                || root instanceof java.util.concurrent.CompletionException)
                && root.getCause() != null) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }

    private static String snippet(String body) {
        if (body == null) {
            return "(空)";
        }
        String oneLine = body.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 100 ? oneLine : oneLine.substring(0, 100) + "…";
    }

    // ── 最小 JSON ───────────────────────────────────────────────────────────
    // 只用得到 Map / List / String / Long / Double / Boolean / null。
    // 不引 Jackson 的理由：单文件脚本要能 java 直接跑，不能要求 classpath。

    static final class Json {

        static Object parse(String text) {
            Parser parser = new Parser(text);
            parser.ws();
            Object value = parser.value();
            parser.ws();
            return value;
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> map(Object node) {
            return node instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        }

        static String str(Object node) {
            return node == null ? "" : String.valueOf(node);
        }

        static long lng(Object node, long fallback) {
            if (node instanceof Number n) {
                return n.longValue();
            }
            if (node instanceof String s && !s.isBlank()) {
                try {
                    return Long.parseLong(s.trim());
                } catch (NumberFormatException ignored) {
                    return fallback;
                }
            }
            return fallback;
        }

        /**
         * 两种上报格式都要吃下：
         * array  → [{"type":"text","data":{"text":"..."}}]
         * string → "[CQ:at,qq=1] 签到了吗"
         */
        static String textOf(Object message) {
            if (message instanceof String cq) {
                return cqUnescape(cq.replaceAll("\\[CQ:[^\\]]*]", "")).trim();
            }
            if (message instanceof List<?> segments) {
                StringBuilder text = new StringBuilder();
                for (Object segment : segments) {
                    Map<String, Object> seg = map(segment);
                    if (!"text".equals(str(seg.get("type")))) {
                        continue;   // at / image / face 一律不转给模型
                    }
                    text.append(str(map(seg.get("data")).get("text")));
                }
                return text.toString().trim();
            }
            return "";
        }

        private static String cqUnescape(String text) {
            return text.replace("&#91;", "[")
                    .replace("&#93;", "]")
                    .replace("&#44;", ",")
                    .replace("&amp;", "&");
        }

        static String write(Object value) {
            StringBuilder sb = new StringBuilder();
            writeTo(sb, value);
            return sb.toString();
        }

        private static void writeTo(StringBuilder sb, Object value) {
            switch (value) {
                case null -> sb.append("null");
                case String s -> quote(sb, s);
                case Number n -> sb.append(n);
                case Boolean b -> sb.append(b);
                case Map<?, ?> m -> {
                    sb.append('{');
                    boolean first = true;
                    for (Map.Entry<?, ?> entry : m.entrySet()) {
                        if (!first) {
                            sb.append(',');
                        }
                        first = false;
                        quote(sb, String.valueOf(entry.getKey()));
                        sb.append(':');
                        writeTo(sb, entry.getValue());
                    }
                    sb.append('}');
                }
                case Iterable<?> list -> {
                    sb.append('[');
                    boolean first = true;
                    for (Object item : list) {
                        if (!first) {
                            sb.append(',');
                        }
                        first = false;
                        writeTo(sb, item);
                    }
                    sb.append(']');
                }
                default -> quote(sb, String.valueOf(value));
            }
        }

        private static void quote(StringBuilder sb, String s) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                    }
                }
            }
            sb.append('"');
        }

        private static final class Parser {

            private final String text;

            private int i;

            Parser(String text) {
                this.text = text;
            }

            void ws() {
                while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
                    i++;
                }
            }

            Object value() {
                ws();
                if (i >= text.length()) {
                    throw new IllegalArgumentException("JSON 提前结束");
                }
                char c = text.charAt(i);
                return switch (c) {
                    case '{' -> object();
                    case '[' -> array();
                    case '"' -> string();
                    case 't' -> literal("true", Boolean.TRUE);
                    case 'f' -> literal("false", Boolean.FALSE);
                    case 'n' -> literal("null", null);
                    default -> number();
                };
            }

            private Map<String, Object> object() {
                Map<String, Object> map = new LinkedHashMap<>();
                i++;
                ws();
                if (i < text.length() && text.charAt(i) == '}') {
                    i++;
                    return map;
                }
                while (true) {
                    ws();
                    String key = string();
                    ws();
                    if (i >= text.length() || text.charAt(i) != ':') {
                        throw new IllegalArgumentException("JSON 对象缺少 : 于 " + i);
                    }
                    i++;
                    ws();
                    map.put(key, value());
                    ws();
                    if (i >= text.length()) {
                        throw new IllegalArgumentException("JSON 对象未闭合");
                    }
                    char c = text.charAt(i++);
                    if (c == '}') {
                        return map;
                    }
                    if (c != ',') {
                        throw new IllegalArgumentException("JSON 对象缺少 , 于 " + i);
                    }
                }
            }

            private List<Object> array() {
                List<Object> list = new ArrayList<>();
                i++;
                ws();
                if (i < text.length() && text.charAt(i) == ']') {
                    i++;
                    return list;
                }
                while (true) {
                    ws();
                    list.add(value());
                    ws();
                    if (i >= text.length()) {
                        throw new IllegalArgumentException("JSON 数组未闭合");
                    }
                    char c = text.charAt(i++);
                    if (c == ']') {
                        return list;
                    }
                    if (c != ',') {
                        throw new IllegalArgumentException("JSON 数组缺少 , 于 " + i);
                    }
                }
            }

            private String string() {
                if (i >= text.length() || text.charAt(i) != '"') {
                    throw new IllegalArgumentException("JSON 期望字符串 于 " + i);
                }
                i++;
                StringBuilder sb = new StringBuilder();
                while (i < text.length()) {
                    char c = text.charAt(i++);
                    if (c == '"') {
                        return sb.toString();
                    }
                    if (c != '\\') {
                        sb.append(c);
                        continue;
                    }
                    if (i >= text.length()) {
                        break;
                    }
                    char escape = text.charAt(i++);
                    switch (escape) {
                        case '"', '\\', '/' -> sb.append(escape);
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (i + 4 > text.length()) {
                                throw new IllegalArgumentException("JSON \\u 转义不完整");
                            }
                            sb.append((char) Integer.parseInt(text.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> throw new IllegalArgumentException("非法转义 \\" + escape);
                    }
                }
                throw new IllegalArgumentException("JSON 字符串未闭合");
            }

            private Object literal(String word, Object value) {
                if (!text.startsWith(word, i)) {
                    throw new IllegalArgumentException("JSON 非法字面量 于 " + i);
                }
                i += word.length();
                return value;
            }

            private Object number() {
                int start = i;
                while (i < text.length() && "+-0123456789.eE".indexOf(text.charAt(i)) >= 0) {
                    i++;
                }
                if (start == i) {
                    throw new IllegalArgumentException("JSON 非法字符 于 " + i);
                }
                String raw = text.substring(start, i);
                if (raw.indexOf('.') < 0 && raw.indexOf('e') < 0 && raw.indexOf('E') < 0) {
                    try {
                        return Long.parseLong(raw);
                    } catch (NumberFormatException ignored) {
                        // 超出 long 就当 double
                    }
                }
                return Double.parseDouble(raw);
            }
        }
    }

    // ── 自检 ────────────────────────────────────────────────────────────────

    /**
     * 离线自检：把纯函数单独跑一遍。
     *
     * <p>为什么要它：JSON 解析、切片、白名单闸门这三块出错时，
     * 现场症状是"机器人不说话"，跟协议端没连上、白名单写错长得一样。
     * 先让这三块有独立的绿灯，排障时才不用猜。
     */
    static final class Selftest {

        private static int passed;

        private static final List<String> failed = new ArrayList<>();

        static void run() {
            parseEvent();
            parseCqString();
            sessionIds();
            chunking();
            gate();
            writePayload();
            errorText();

            System.out.println();
            if (failed.isEmpty()) {
                System.out.println("自检通过：" + passed + " 项");
                return;
            }
            System.out.println("自检失败：" + failed.size() + " 项 / 共 " + (passed + failed.size()) + " 项");
            failed.forEach(f -> System.out.println("  ✗ " + f));
            System.exit(1);
        }

        private static void check(String name, boolean ok) {
            if (ok) {
                passed++;
            } else {
                failed.add(name);
            }
        }

        private static void parseEvent() {
            String event = """
                    {"post_type":"message","message_type":"private","user_id":10001,"message_id":42,
                     "message":[{"type":"text","data":{"text":"帮我签到\\n明天也要"}},
                                {"type":"image","data":{"file":"a.png"}}],
                     "sender":{"nickname":"萝卜"}}
                    """;
            Map<String, Object> m = Json.map(Json.parse(event));
            check("事件：post_type", "message".equals(Json.str(m.get("post_type"))));
            check("事件：user_id", Json.lng(m.get("user_id"), -1L) == 10001L);
            check("事件：嵌套 sender.nickname", "萝卜".equals(Json.str(Json.map(m.get("sender")).get("nickname"))));
            check("事件：只取 text 段", "帮我签到\n明天也要".equals(Json.textOf(m.get("message"))));
        }

        private static void parseCqString() {
            Object message = Json.parse("\"[CQ:at,qq=1] 签到了吗&#91;测试&#93;\"");
            check("CQ：去码 + 反转义",
                    "签到了吗[测试]".equals(Json.textOf(message)));
        }

        private static void sessionIds() {
            check("会话：私聊按人", "qq:c2c:10001".equals(Java_sessionOf("private", 10001L, 0L)));
            check("会话：群聊按群+人", "qq:group:555:10001".equals(Java_sessionOf("group", 10001L, 555L)));
        }

        /** 与 Session#handle 里的规则保持一致；复制一份是为了让自检不依赖网络对象。 */
        private static String Java_sessionOf(String type, long uid, long gid) {
            return "group".equals(type) ? "qq:group:" + gid + ":" + uid : "qq:c2c:" + uid;
        }

        private static void chunking() {
            String longLine = "签".repeat(2000);
            List<String> pieces = Text.split(longLine, 800);
            check("切片：不超长", pieces.stream().allMatch(p -> p.length() <= 800));
            check("切片：不丢字", String.join("", pieces).equals(longLine));

            String multiline = "第一行\n第二行\n" + "长".repeat(1000) + "\n最后一行";
            List<String> lines = Text.split(multiline, 100);
            check("切片：多行不超长", lines.stream().allMatch(p -> p.length() <= 100));
            check("切片：多行不丢字",
                    String.join("", lines).replace("\n", "").equals(multiline.replace("\n", "")));
            check("切片：空串不产生消息", Text.split("", 800).isEmpty());
        }

        private static void gate() {
            Config empty = new Config("ws://x", "", "http://x", Set.of(), false, 800, true);
            check("闸门：白名单为空 → 私聊也拒绝", !Gate.allowed(empty, "private", 10001L, 0L));

            Config self = new Config("ws://x", "", "http://x", Set.of(10001L), false, 800, true);
            check("闸门：白名单私聊放行", Gate.allowed(self, "private", 10001L, 0L));
            check("闸门：非白名单拒绝", !Gate.allowed(self, "private", 20002L, 0L));
            check("闸门：群聊默认关闭", !Gate.allowed(self, "group", 10001L, 555L));

            Config group = new Config("ws://x", "", "http://x", Set.of(10001L), true, 800, true);
            check("闸门：群聊开启后仍查人", Gate.allowed(group, "group", 10001L, 555L));
            check("闸门：群聊开启也不放陌生人", !Gate.allowed(group, "group", 20002L, 555L));
        }

        private static void writePayload() {
            String tricky = "引号\"换行\n反斜杠\\方括号[]";

            Map<String, Object> segment = new LinkedHashMap<>();
            segment.put("type", "text");
            segment.put("data", Map.of("text", tricky));

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("user_id", 10001L);
            params.put("message", List.of(segment));

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("action", "send_private_msg");
            payload.put("params", params);
            payload.put("echo", "e1");

            String json = Json.write(payload);
            Map<String, Object> back = Json.map(Json.parse(json));
            check("序列化：action", "send_private_msg".equals(Json.str(back.get("action"))));

            Object message = Json.map(back.get("params")).get("message");
            Object first = message instanceof List<?> list && !list.isEmpty() ? list.get(0) : null;
            String text = Json.str(Json.map(Json.map(first).get("data")).get("text"));
            check("序列化：转义往返", tricky.equals(text));
        }

        /** 现场踩过：ConnectException 的 message 是 null，回给用户就成了「【出错了】null」。 */
        private static void errorText() {
            check("报错：无 message 的异常退回类型名",
                    "ConnectException".equals(describe(new java.net.ConnectException())));
            check("报错：ExecutionException 要拆到底",
                    "ConnectException".equals(describe(new java.util.concurrent.ExecutionException(
                            new java.net.ConnectException()))));
            check("报错：有 message 就原样给",
                    "连接被拒绝".equals(describe(new IllegalStateException("连接被拒绝"))));
            check("报错：正文压成一行并截断",
                    snippet("<html>\n  <body>404</body>\n</html>").equals("<html> <body>404</body> </html>"));
        }

        private Selftest() {
        }
    }

    private QQBridge() {
    }
}
