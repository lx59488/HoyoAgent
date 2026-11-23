package com.luobo.hoyoagent.agent.transcript;

import com.luobo.hoyoagent.common.conversation.ConversationKeys;
import com.luobo.hoyoagent.common.storage.LocalFileStore;
import com.luobo.hoyoagent.common.storage.SqliteDb;
import com.luobo.hoyoagent.common.storage.SqliteProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * SQLite 版聊天历史：一轮一行，列表按会话聚合。
 *
 * <p>append 用事务取 {@code max(seq)+1}；同一会话的写入在 {@code SessionSerializer} 里已经串行，
 * 所以这里不再为并发抢同一个 seq 做额外协调。JSONL 时代的旧文件在首次启动时导入一次。</p>
 */
@Component
@ConditionalOnProperty(name = "hoyo.storage.backend", havingValue = "sqlite")
public class SqliteTranscriptStore implements TranscriptStore {

    private static final Logger log = LoggerFactory.getLogger(SqliteTranscriptStore.class);

    private static final int PREVIEW_CHARS = 40;

    private static final int MAX_LIMIT = 500;

    private static final String MIGRATED_KEY = "transcript.migrated";

    private final SqliteDb db;

    private final TranscriptProperties properties;

    private final ObjectMapper mapper;

    private final Path legacyDir;

    @Autowired
    public SqliteTranscriptStore(SqliteProperties sqliteProperties,
                                 @Value("${hoyo.state-dir:}") String stateDir,
                                 ObjectMapper mapper,
                                 TranscriptProperties properties) {
        this.db = new SqliteDb(sqliteProperties.path(), stateDir, sqliteProperties.busyTimeout());
        this.db.initSchema();
        this.properties = properties;
        this.mapper = mapper;
        this.legacyDir = properties.dir().isBlank()
                ? LocalFileStore.stateRoot(stateDir).resolve("transcripts")
                : Path.of(properties.dir());
        log.info("[TRANSCRIPT] SQLite 后端 db={} 保留期={}", db.path(), properties.retention());
    }

    @Override
    public void append(TranscriptExchange exchange) {
        if (!properties.recording() || exchange == null
                || exchange.sessionId() == null || exchange.sessionId().isBlank()) {
            return;
        }
        String at = Instant.now().toString();
        try (Connection connection = db.open()) {
            connection.setAutoCommit(false);
            try {
                long seq = nextSeq(connection, exchange.sessionId());
                String conversationId = ConversationKeys.conversationIdOf(exchange.sessionId());
                String kind = ConversationKeys.kindOf(exchange.sessionId());
                insertTurn(connection, seq, at, exchange, conversationId, kind);
                upsertSession(connection, at, exchange, conversationId, kind);
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (Exception e) {
            log.warn("[TRANSCRIPT] 写入 SQLite 失败 session={}", exchange.sessionId(), e);
        }
    }

    private long nextSeq(Connection connection, String sessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(MAX(seq), 0) + 1 FROM transcript_turn WHERE session_id = ?")) {
            statement.setString(1, sessionId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private void insertTurn(Connection connection, long seq, String at, TranscriptExchange exchange,
                            String conversationId, String kind) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO transcript_turn(session_id, seq, at, user_id, conversation_id, kind,
                                            user_text, assistant_text, tools, error)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
            statement.setString(1, exchange.sessionId());
            statement.setLong(2, seq);
            statement.setString(3, at);
            statement.setString(4, exchange.userId() == null ? "" : exchange.userId());
            statement.setString(5, conversationId);
            statement.setString(6, kind);
            statement.setString(7, exchange.user() == null ? "" : exchange.user());
            statement.setString(8, exchange.assistant());
            statement.setString(9, mapper.writeValueAsString(exchange.tools()));
            statement.setString(10, exchange.error());
            statement.executeUpdate();
        }
    }

    private void upsertSession(Connection connection, String at, TranscriptExchange exchange,
                               String conversationId, String kind) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO transcript_session(session_id, user_id, conversation_id, kind, turns, last_at, preview)
                VALUES (?, ?, ?, ?, 1, ?, ?)
                ON CONFLICT(session_id) DO UPDATE SET
                    user_id = excluded.user_id,
                    conversation_id = excluded.conversation_id,
                    kind = excluded.kind,
                    turns = transcript_session.turns + 1,
                    last_at = excluded.last_at,
                    preview = excluded.preview""")) {
            statement.setString(1, exchange.sessionId());
            statement.setString(2, exchange.userId() == null ? "" : exchange.userId());
            statement.setString(3, conversationId);
            statement.setString(4, kind);
            statement.setString(5, at);
            statement.setString(6, preview(exchange.user()));
            statement.executeUpdate();
        }
    }

    @Override
    public List<TranscriptSession> sessions() {
        List<TranscriptSession> sessions = new ArrayList<>();
        String sql = """
                SELECT session_id, user_id, conversation_id, kind, turns, last_at, preview
                FROM transcript_session
                ORDER BY last_at DESC, session_id ASC""";
        try (Connection connection = db.open();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                sessions.add(new TranscriptSession(
                        result.getString("session_id"),
                        result.getString("user_id"),
                        result.getString("conversation_id"),
                        result.getString("kind"),
                        result.getInt("turns"),
                        result.getString("last_at"),
                        result.getString("preview")));
            }
        } catch (Exception e) {
            log.warn("[TRANSCRIPT] 读取 SQLite 会话列表失败", e);
        }
        return sessions;
    }

    @Override
    public TranscriptPage read(String sessionId, long before, int limit) {
        int size = limit <= 0 ? 100 : Math.min(limit, MAX_LIMIT);
        List<TranscriptTurn> turns = new ArrayList<>();
        String userId = "";
        try (Connection connection = db.open()) {
            userId = sessionUserId(connection, sessionId);
            String sql = "SELECT seq, at, session_id, user_id, conversation_id, kind, "
                    + "user_text, assistant_text, tools, error "
                    + "FROM transcript_turn WHERE session_id = ?"
                    + (before > 0 ? " AND seq < ?" : "")
                    + " ORDER BY seq DESC LIMIT ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int index = 1;
                statement.setString(index++, sessionId);
                if (before > 0) {
                    statement.setLong(index++, before);
                }
                statement.setInt(index, size);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        turns.add(new TranscriptTurn(
                                result.getLong("seq"),
                                result.getString("at"),
                                result.getString("session_id"),
                                result.getString("user_id"),
                                result.getString("conversation_id"),
                                result.getString("kind"),
                                result.getString("user_text"),
                                result.getString("assistant_text"),
                                readTools(result.getString("tools")),
                                result.getString("error")));
                    }
                }
            }
            Collections.reverse(turns);
            boolean hasMore = !turns.isEmpty()
                    && countOlder(connection, sessionId, turns.get(0).seq()) > 0;
            return new TranscriptPage(sessionId, userId, turns, hasMore);
        } catch (Exception e) {
            log.warn("[TRANSCRIPT] 读取 SQLite 聊天历史失败 session={}", sessionId, e);
            return new TranscriptPage(sessionId, userId, List.of(), false);
        }
    }

    private String sessionUserId(Connection connection, String sessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT user_id FROM transcript_session WHERE session_id = ?")) {
            statement.setString(1, sessionId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString("user_id") : "";
            }
        }
    }

    private long countOlder(Connection connection, String sessionId, long seq) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM transcript_turn WHERE session_id = ? AND seq < ?")) {
            statement.setString(1, sessionId);
            statement.setLong(2, seq);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private List<String> readTools(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, new tools.jackson.core.type.TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    @Scheduled(cron = "0 40 4 * * *", zone = "Asia/Shanghai")
    public void purgeExpired() {
        purgeBefore(Instant.now().minus(properties.retention()));
    }

    /** 单测用：显式给截止时间，避免依赖系统时钟。 */
    void purgeBefore(Instant cutoff) {
        try (Connection connection = db.open()) {
            int removed;
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM transcript_turn WHERE at < ?")) {
                statement.setString(1, cutoff.toString());
                removed = statement.executeUpdate();
            }
            if (removed > 0) {
                rebuildSessions(connection);
                log.info("[TRANSCRIPT] 清理 SQLite 聊天历史 {} 轮", removed);
            }
        } catch (Exception e) {
            log.warn("[TRANSCRIPT] 清理 SQLite 聊天历史失败", e);
        }
    }

    /**
     * 首次启动把 JSONL 旧文件导入 SQLite；meta 里记标记，之后不再扫目录。
     * 导入后重建会话聚合，保证 turns/last_at/preview 和明细一致。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void migrateFromLegacy() {
        if (!properties.recording()) {
            return;
        }
        try (Connection connection = db.open()) {
            if (readMeta(connection, MIGRATED_KEY) != null) {
                return;
            }
            long imported = 0;
            if (Files.isDirectory(legacyDir)) {
                List<Path> files;
                try (Stream<Path> stream = Files.list(legacyDir)) {
                    files = stream.filter(path -> path.getFileName().toString().endsWith(".jsonl")).toList();
                }
                for (Path file : files) {
                    imported += importFile(connection, file);
                }
            }
            rebuildSessions(connection);
            writeMeta(connection, MIGRATED_KEY, Instant.now().toString());
            if (imported > 0) {
                log.info("[TRANSCRIPT] 从 JSONL 迁移 {} 轮聊天历史", imported);
            }
        } catch (Exception e) {
            log.warn("[TRANSCRIPT] JSONL 迁移失败", e);
        }
    }

    private int importFile(Connection connection, Path file) {
        int imported = 0;
        try {
            for (String line : Files.readAllLines(file)) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node;
                try {
                    node = mapper.readTree(line);
                } catch (Exception e) {
                    continue;
                }
                long seq = node.path("seq").asLong(0);
                String sessionId = node.path("sessionId").asString(null);
                if (seq <= 0 || sessionId == null || sessionId.isBlank()) {
                    continue;
                }
                String kind = node.path("kind").asString("");
                if (kind.isBlank()) {
                    kind = ConversationKeys.kindOf(sessionId);
                }
                String conversationId = node.path("conversationId").asString("");
                if (conversationId.isBlank()) {
                    conversationId = ConversationKeys.conversationIdOf(sessionId);
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT OR IGNORE INTO transcript_turn
                            (session_id, seq, at, user_id, conversation_id, kind,
                             user_text, assistant_text, tools, error)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
                    statement.setString(1, sessionId);
                    statement.setLong(2, seq);
                    statement.setString(3, node.path("at").asString(Instant.now().toString()));
                    statement.setString(4, node.path("userId").asString(""));
                    statement.setString(5, conversationId);
                    statement.setString(6, kind);
                    statement.setString(7, node.path("user").asString(""));
                    statement.setString(8, nullableText(node, "assistant"));
                    statement.setString(9, node.path("tools").isArray() ? node.path("tools").toString() : "[]");
                    statement.setString(10, nullableText(node, "error"));
                    imported += statement.executeUpdate();
                }
            }
        } catch (Exception e) {
            log.warn("[TRANSCRIPT] 导入 JSONL 文件失败 file={}", file, e);
        }
        return imported;
    }

    private void rebuildSessions(Connection connection) throws SQLException {
        List<String> ids = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT DISTINCT session_id FROM transcript_turn");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                ids.add(result.getString(1));
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM transcript_session")) {
            statement.executeUpdate();
        }
        for (String id : ids) {
            String userId;
            int turns;
            String lastAt;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT user_id, COUNT(*), MAX(at) FROM transcript_turn WHERE session_id = ?")) {
                statement.setString(1, id);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    userId = result.getString(1);
                    turns = result.getInt(2);
                    lastAt = result.getString(3);
                }
            }
            String preview;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT user_text FROM transcript_turn WHERE session_id = ? ORDER BY seq DESC LIMIT 1")) {
                statement.setString(1, id);
                try (ResultSet result = statement.executeQuery()) {
                    preview = result.next() ? preview(result.getString(1)) : "";
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO transcript_session(session_id, user_id, conversation_id, kind,
                                                   turns, last_at, preview)
                    VALUES (?, ?, ?, ?, ?, ?, ?)""")) {
                statement.setString(1, id);
                statement.setString(2, userId == null ? "" : userId);
                statement.setString(3, ConversationKeys.conversationIdOf(id));
                statement.setString(4, ConversationKeys.kindOf(id));
                statement.setInt(5, turns);
                statement.setString(6, lastAt);
                statement.setString(7, preview);
                statement.executeUpdate();
            }
        }
    }

    private String readMeta(Connection connection, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT v FROM meta WHERE k = ?")) {
            statement.setString(1, key);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    private void writeMeta(Connection connection, String key, String value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO meta(k, v) VALUES (?, ?)
                ON CONFLICT(k) DO UPDATE SET v = excluded.v""")) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeUpdate();
        }
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asString(null);
    }

    private static String preview(String text) {
        if (text == null) {
            return "";
        }
        String line = text.replaceAll("\\s+", " ").trim();
        return line.length() <= PREVIEW_CHARS ? line : line.substring(0, PREVIEW_CHARS) + "…";
    }
}
