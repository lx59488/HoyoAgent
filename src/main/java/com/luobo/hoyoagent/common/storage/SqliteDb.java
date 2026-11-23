package com.luobo.hoyoagent.common.storage;

import com.luobo.hoyoagent.common.conversation.ConversationKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite 连接与 schema 基座。
 *
 * <p>单文件、WAL、busy_timeout：够单机 Docker 的多线程读写；多实例仍要靠外部唯一写者，
 * 真正的跨进程协调不在这一层解决。</p>
 */
public final class SqliteDb {

    private static final Logger log = LoggerFactory.getLogger(SqliteDb.class);

    private final Path path;

    private final Duration busyTimeout;

    public SqliteDb(String configuredPath, String stateDir, Duration busyTimeout) {
        this.path = resolve(configuredPath, stateDir);
        this.busyTimeout = busyTimeout == null ? Duration.ofSeconds(5) : busyTimeout;
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception e) {
            throw new IllegalStateException("无法创建 SQLite 目录: " + path, e);
        }
    }

    private static Path resolve(String configuredPath, String stateDir) {
        if (configuredPath != null && !configuredPath.isBlank()) {
            return Path.of(configuredPath.trim());
        }
        return LocalFileStore.stateRoot(stateDir).resolve("hoyo.db");
    }

    public Path path() {
        return path;
    }

    /** 每次操作新开连接：SQLite 连接很轻，避免长连接把 WAL 一直挂住。 */
    public Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = " + Math.max(1L, busyTimeout.toMillis()));
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = NORMAL");
            statement.execute("PRAGMA foreign_keys = ON");
        }
        return connection;
    }

    public void initSchema() {
        try (Connection connection = open()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS memory_session (
                            session_id TEXT PRIMARY KEY,
                            updated_at TEXT NOT NULL,
                            payload    TEXT NOT NULL
                        )""");
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_memory_updated ON memory_session(updated_at)""");

                statement.execute("""
                        CREATE TABLE IF NOT EXISTS transcript_session (
                            session_id      TEXT PRIMARY KEY,
                            user_id         TEXT NOT NULL,
                            conversation_id TEXT NOT NULL DEFAULT '',
                            kind            TEXT NOT NULL DEFAULT 'c2c',
                            turns           INTEGER NOT NULL,
                            last_at         TEXT NOT NULL,
                            preview         TEXT NOT NULL
                        )""");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS transcript_turn (
                            session_id      TEXT NOT NULL,
                            seq             INTEGER NOT NULL,
                            at              TEXT NOT NULL,
                            user_id         TEXT NOT NULL,
                            conversation_id TEXT NOT NULL DEFAULT '',
                            kind            TEXT NOT NULL DEFAULT 'c2c',
                            user_text       TEXT NOT NULL,
                            assistant_text  TEXT,
                            tools           TEXT NOT NULL,
                            error           TEXT,
                            PRIMARY KEY (session_id, seq)
                        )""");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)""");
            }
            // P1 早期建的库没有这两列：先补列，再建依赖新列的索引，最后把老行回填成「私聊」。
            ensureColumn(connection, "transcript_session", "conversation_id",
                    "conversation_id TEXT NOT NULL DEFAULT ''");
            ensureColumn(connection, "transcript_session", "kind",
                    "kind TEXT NOT NULL DEFAULT 'c2c'");
            ensureColumn(connection, "transcript_turn", "conversation_id",
                    "conversation_id TEXT NOT NULL DEFAULT ''");
            ensureColumn(connection, "transcript_turn", "kind",
                    "kind TEXT NOT NULL DEFAULT 'c2c'");
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_turn_at ON transcript_turn(at)""");
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_turn_user ON transcript_turn(user_id)""");
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_turn_conversation
                        ON transcript_turn(conversation_id, seq)""");
            }
            backfillConversation(connection);
        } catch (SQLException e) {
            throw new IllegalStateException("初始化 SQLite schema 失败: " + path, e);
        }
        log.info("[SQLITE] 存储已就绪: {}", path);
    }

    private static void ensureColumn(Connection connection, String table, String column, String definition)
            throws SQLException {
        boolean exists = false;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) {
                if (column.equalsIgnoreCase(result.getString("name"))) {
                    exists = true;
                    break;
                }
            }
        }
        if (!exists) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE " + table + " ADD COLUMN " + definition);
            }
            log.info("[SQLITE] 迁移表 {}：补列 {}", table, column);
        }
    }

    /** 老数据没有 conversation_id/kind：按 sessionId 推导后回填，只处理空值。 */
    private static void backfillConversation(Connection connection) throws SQLException {
        List<String> ids = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT DISTINCT session_id FROM transcript_turn WHERE conversation_id = ''")) {
            while (result.next()) {
                ids.add(result.getString(1));
            }
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT session_id FROM transcript_session WHERE conversation_id = ''")) {
            while (result.next()) {
                String id = result.getString(1);
                if (!ids.contains(id)) {
                    ids.add(id);
                }
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        try (PreparedStatement turns = connection.prepareStatement("""
                     UPDATE transcript_turn
                     SET conversation_id = ?, kind = ?
                     WHERE session_id = ? AND conversation_id = ''""");
             PreparedStatement sessions = connection.prepareStatement("""
                     UPDATE transcript_session
                     SET conversation_id = ?, kind = ?
                     WHERE session_id = ? AND conversation_id = ''""")) {
            for (String id : ids) {
                String conversationId = ConversationKeys.conversationIdOf(id);
                String kind = ConversationKeys.kindOf(id);
                turns.setString(1, conversationId);
                turns.setString(2, kind);
                turns.setString(3, id);
                turns.addBatch();
                sessions.setString(1, conversationId);
                sessions.setString(2, kind);
                sessions.setString(3, id);
                sessions.addBatch();
            }
            turns.executeBatch();
            sessions.executeBatch();
        }
        log.info("[SQLITE] 回填 conversation_id/kind：{} 个会话", ids.size());
    }
}
