package com.luobo.hoyoagent.agent.transcript;

import com.luobo.hoyoagent.common.conversation.ConversationKeys;
import com.luobo.hoyoagent.common.log.Mask;
import com.luobo.hoyoagent.common.storage.LocalFileStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 本地 append-only 聊天历史：一个会话一个 {@code .jsonl}，一行一轮。
 *
 * <p>为什么不是"再加一个普通 JSON 文件"：整份重写会在崩溃时丢整段历史，而且并发写回互相覆盖。
 * JSONL 只追加，坏一行只坏一行；读的时候跳过坏行，前面的记录照样在。
 *
 * <p>为什么文件名还是哈希：sessionId 里有冒号和 openid，直接当文件名等于把平台账号写进文件系统
 * （和 {@code FileMemoryStore} 同一套路，sha256 前 32 位）。
 *
 * <p>锁：单进程、低频，一把 {@link ReentrantLock} 同时挡住"读到半行"和"seq 算重"。
 * Windows 上别的线程正写时读同一个文件会报共享冲突，所以读也进锁——历史不是热路径，不值得为它做无锁。
 */
@Component
@ConditionalOnProperty(name = "hoyo.storage.backend", havingValue = "file", matchIfMissing = true)
public class FileTranscriptStore implements TranscriptStore {

    private static final Logger log = LoggerFactory.getLogger(FileTranscriptStore.class);

    private static final String SUFFIX = ".jsonl";

    private static final int HASH_CHARS = 32;

    private static final int PREVIEW_CHARS = 40;

    private static final int MAX_LIMIT = 500;

    private final LocalFileStore store;

    private final Duration retention;

    private final boolean enabled;

    private final ObjectMapper mapper;

    /** 会话元数据缓存：第一次 list 时扫盘建起来，append/purge 增量维护。 */
    private final Map<String, Meta> meta = new ConcurrentHashMap<>();

    private final ReentrantLock lock = new ReentrantLock();

    private volatile boolean loaded;

    @Autowired
    public FileTranscriptStore(TranscriptProperties properties,
                               @Value("${hoyo.state-dir:}") String stateDir,
                               ObjectMapper mapper) {
        this(LocalFileStore.resolve(properties.dir(),
                        LocalFileStore.stateRoot(stateDir).resolve("transcripts")),
                properties.retention(), properties.recording(), mapper);
    }

    /** 单测用：直接给目录。 */
    FileTranscriptStore(String dir, Duration retention, ObjectMapper mapper) {
        this(dir, retention, true, mapper);
    }

    FileTranscriptStore(String dir, Duration retention, boolean enabled, ObjectMapper mapper) {
        this(LocalFileStore.at(Path.of(dir)), retention, enabled, mapper);
    }

    private FileTranscriptStore(LocalFileStore store, Duration retention, boolean enabled, ObjectMapper mapper) {
        this.store = store;
        this.retention = retention;
        this.enabled = enabled;
        this.mapper = mapper;
        if (enabled) {
            store.ensureRoot();
            log.info("[TRANSCRIPT] 落盘目录={} 保留期={}", store.root(), retention);
        } else {
            log.info("[TRANSCRIPT] 已关闭（hoyo.transcript.enabled=false），不记录聊天历史");
        }
    }

    @Override
    public void append(TranscriptExchange exchange) {
        if (!enabled || exchange == null || exchange.sessionId() == null || exchange.sessionId().isBlank()) {
            return;
        }
        lock.lock();
        try {
            Meta current = meta.get(exchange.sessionId());
            if (current == null) {
                current = scan(exchange.sessionId());
            }
            long seq = current == null ? 1 : current.lastSeq() + 1;
            String at = Instant.now().toString();
            String conversationId = ConversationKeys.conversationIdOf(exchange.sessionId());
            String kind = ConversationKeys.kindOf(exchange.sessionId());
            TranscriptTurn turn = new TranscriptTurn(seq, at, exchange.sessionId(), exchange.userId(),
                    conversationId, kind, exchange.user(), exchange.assistant(), exchange.tools(),
                    exchange.error());

            Files.writeString(fileOf(exchange.sessionId()), mapper.writeValueAsString(turn) + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);

            meta.put(exchange.sessionId(), new Meta(exchange.sessionId(), exchange.userId(),
                    conversationId, kind, (current == null ? 0 : current.turns()) + 1, seq, at,
                    preview(exchange.user())));
        } catch (IOException | RuntimeException e) {
            // 历史是观测，不是业务事实：写失败只降级成日志，不能把这一轮聊天弄挂
            log.warn("[TRANSCRIPT] 写聊天记录失败 session={}: {}", Mask.id(exchange.sessionId()), e.toString());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<TranscriptSession> sessions() {
        if (!enabled) {
            return List.of();
        }
        lock.lock();
        try {
            if (!loaded) {
                reloadAll();
            }
            return meta.values().stream()
                    .map(m -> new TranscriptSession(m.sessionId(), m.userId(), m.conversationId(),
                            m.kind(), m.turns(), m.lastAt(), m.preview()))
                    .sorted(Comparator.comparing(TranscriptSession::lastAt,
                                    Comparator.nullsLast(Comparator.naturalOrder()))
                            .reversed()
                            .thenComparing(TranscriptSession::sessionId))
                    .toList();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public TranscriptPage read(String sessionId, long before, int limit) {
        if (!enabled || sessionId == null || sessionId.isBlank()) {
            return new TranscriptPage(sessionId, "", List.of(), false);
        }
        int size = Math.max(1, Math.min(limit, MAX_LIMIT));
        lock.lock();
        try {
            List<TranscriptTurn> all = readAll(fileOf(sessionId));
            List<TranscriptTurn> eligible = before > 0
                    ? all.stream().filter(turn -> turn.seq() < before).toList()
                    : all;
            int from = Math.max(0, eligible.size() - size);
            List<TranscriptTurn> page = List.copyOf(eligible.subList(from, eligible.size()));
            String userId = all.isEmpty() ? "" : all.get(all.size() - 1).userId();
            return new TranscriptPage(sessionId, userId, page, from > 0);
        } finally {
            lock.unlock();
        }
    }

    /** 每天 04:40 清一次超过保留期的会话文件。按修改时间算，不解析内容。 */
    @Scheduled(cron = "0 40 4 * * *", zone = "Asia/Shanghai")
    public void purgeExpired() {
        if (!enabled) {
            return;
        }
        Instant cutoff = Instant.now().minus(retention);
        lock.lock();
        try {
            for (Path file : LocalFileStore.listFiles(store.root(), SUFFIX)) {
                try {
                    if (Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) {
                        Files.deleteIfExists(file);
                        meta.entrySet().removeIf(entry -> fileOf(entry.getKey()).equals(file));
                        log.info("[TRANSCRIPT] 清理过期聊天记录 {}", file.getFileName());
                    }
                } catch (IOException e) {
                    log.warn("[TRANSCRIPT] 清理失败 {}: {}", file.getFileName(), e.toString());
                }
            }
        } catch (IOException e) {
            log.warn("[TRANSCRIPT] 扫描聊天记录目录失败: {}", e.toString());
        } finally {
            lock.unlock();
        }
    }

    Path fileOf(String sessionId) {
        return store.fileOf(sessionId, HASH_CHARS, SUFFIX);
    }

    /** 首次 list 时把整个目录扫一遍；之后靠 append/purge 维护缓存。 */
    private void reloadAll() {
        meta.clear();
        try {
            for (Path file : LocalFileStore.listFiles(store.root(), SUFFIX)) {
                Meta scanned = scanFile(file);
                if (scanned != null) {
                    meta.put(scanned.sessionId(), scanned);
                }
            }
        } catch (IOException e) {
            log.warn("[TRANSCRIPT] 扫描聊天记录目录失败: {}", e.toString());
        } finally {
            loaded = true;
        }
    }

    /** 冷启动后第一次 append：先读这个会话的文件，才能接着上一个 seq。 */
    private Meta scan(String sessionId) {
        Meta scanned = scanFile(fileOf(sessionId));
        if (scanned == null) {
            return null;
        }
        return new Meta(sessionId, scanned.userId(), scanned.conversationId(), scanned.kind(),
                scanned.turns(), scanned.lastSeq(), scanned.lastAt(), scanned.preview());
    }

    private Meta scanFile(Path file) {
        if (!Files.isReadable(file)) {
            return null;
        }
        try {
            int turns = 0;
            TranscriptTurn last = null;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                TranscriptTurn turn = parse(line);
                if (turn != null) {
                    turns++;
                    last = turn;
                }
            }
            if (last == null) {
                return null;
            }
            String sessionId = last.sessionId() == null || last.sessionId().isBlank()
                    ? file.getFileName().toString()
                    : last.sessionId();
            String conversationId = last.conversationId() == null || last.conversationId().isBlank()
                    ? ConversationKeys.conversationIdOf(sessionId)
                    : last.conversationId();
            String kind = last.kind() == null || last.kind().isBlank()
                    ? ConversationKeys.kindOf(sessionId)
                    : last.kind();
            return new Meta(sessionId, last.userId(), conversationId, kind, turns, last.seq(),
                    last.at(), preview(last.user()));
        } catch (IOException | RuntimeException e) {
            log.warn("[TRANSCRIPT] 读聊天记录失败 {}: {}", file.getFileName(), e.toString());
            return null;
        }
    }

    private List<TranscriptTurn> readAll(Path file) {
        if (!Files.isReadable(file)) {
            return List.of();
        }
        try {
            List<TranscriptTurn> turns = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                TranscriptTurn turn = parse(line);
                if (turn != null) {
                    turns.add(turn);
                }
            }
            return turns;
        } catch (IOException | RuntimeException e) {
            log.warn("[TRANSCRIPT] 读聊天记录失败 {}: {}", file.getFileName(), e.toString());
            return List.of();
        }
    }

    private TranscriptTurn parse(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(line);
            long seq = node.path("seq").asLong(0);
            if (seq <= 0) {
                return null;
            }
            List<String> tools = new ArrayList<>();
            for (JsonNode tool : node.path("tools")) {
                tools.add(tool.asString(""));
            }
            String sessionId = node.path("sessionId").asString("");
            String kind = node.path("kind").asString("");
            if (kind.isBlank()) {
                kind = ConversationKeys.kindOf(sessionId);
            }
            String conversationId = node.path("conversationId").asString("");
            if (conversationId.isBlank()) {
                conversationId = ConversationKeys.conversationIdOf(sessionId);
            }
            return new TranscriptTurn(seq,
                    node.path("at").asString(""),
                    sessionId,
                    node.path("userId").asString(""),
                    conversationId,
                    kind,
                    node.path("user").asString(""),
                    nullableText(node, "assistant"),
                    List.copyOf(tools),
                    nullableText(node, "error"));
        } catch (RuntimeException e) {
            // 一行坏掉不影响整段历史，跳过即可
            log.debug("[TRANSCRIPT] 跳过坏行: {}", e.toString());
            return null;
        }
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asString("");
    }

    private static String preview(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= PREVIEW_CHARS ? oneLine : oneLine.substring(0, PREVIEW_CHARS) + "…";
    }

    private record Meta(String sessionId, String userId, String conversationId, String kind,
                        int turns, long lastSeq, String lastAt, String preview) {
    }
}
