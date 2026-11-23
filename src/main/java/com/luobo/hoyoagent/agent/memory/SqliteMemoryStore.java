package com.luobo.hoyoagent.agent.memory;

import com.luobo.hoyoagent.common.storage.LocalFileStore;
import com.luobo.hoyoagent.common.storage.SqliteDb;
import com.luobo.hoyoagent.common.storage.SqliteProperties;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * SQLite 版记忆后端：一次会话一行，payload 仍是 LangChain4j 的消息 JSON。
 *
 * <p>和 {@link FileMemoryStore} 的语义保持一致：读坏当空、写失败只告警、按 updated_at 清理。
 * 第一次读某个会话时如果 SQLite 里没有、旧文件里有，会懒迁移一行过来。</p>
 */
@Component
@ConditionalOnProperty(name = "hoyo.storage.backend", havingValue = "sqlite")
public class SqliteMemoryStore implements MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(SqliteMemoryStore.class);

    private final SqliteDb db;

    private final Duration retention;

    private final Path legacyDir;

    private final ObjectMapper mapper;

    private volatile FileMemoryStore legacy;

    @Autowired
    public SqliteMemoryStore(SqliteProperties properties,
                             @Value("${hoyo.state-dir:}") String stateDir,
                             @Value("${hoyo.memory.dir:}") String memoryDir,
                             @Value("${hoyo.memory.retention:30d}") Duration retention,
                             ObjectMapper mapper) {
        this.db = new SqliteDb(properties.path(), stateDir, properties.busyTimeout());
        this.db.initSchema();
        this.retention = retention == null ? Duration.ofDays(30) : retention;
        this.legacyDir = LocalFileStore
                .resolve(memoryDir, LocalFileStore.stateRoot(stateDir).resolve("memory"))
                .root();
        this.mapper = mapper;
        log.info("[MEMORY] SQLite 后端 db={} 保留期={}", db.path(), this.retention);
    }

    @Override
    public Optional<Stored> load(String sessionId) {
        Optional<Stored> stored = read(sessionId);
        if (stored.isPresent()) {
            return stored;
        }
        return migrateFromLegacy(sessionId);
    }

    private Optional<Stored> migrateFromLegacy(String sessionId) {
        if (!Files.isDirectory(legacyDir)) {
            return Optional.empty();
        }
        Optional<Stored> old = legacy().load(sessionId);
        old.ifPresent(value -> save(sessionId, value.messages(), value.updatedAt()));
        return old;
    }

    private FileMemoryStore legacy() {
        FileMemoryStore current = legacy;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (legacy == null) {
                legacy = new FileMemoryStore(legacyDir.toString(), retention, mapper);
            }
            return legacy;
        }
    }

    private Optional<Stored> read(String sessionId) {
        String sql = "SELECT updated_at, payload FROM memory_session WHERE session_id = ?";
        try (Connection connection = db.open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                Instant updatedAt = Instant.parse(result.getString("updated_at"));
                List<ChatMessage> messages = ChatMessageDeserializer.messagesFromJson(result.getString("payload"));
                return Optional.of(new Stored(messages, updatedAt));
            }
        } catch (Exception e) {
            log.warn("[MEMORY] 读取 SQLite 失败 session={}", sessionId, e);
            return Optional.empty();
        }
    }

    @Override
    public void save(String sessionId, List<ChatMessage> messages, Instant updatedAt) {
        String sql = """
                INSERT INTO memory_session(session_id, updated_at, payload) VALUES (?, ?, ?)
                ON CONFLICT(session_id) DO UPDATE SET
                    updated_at = excluded.updated_at,
                    payload = excluded.payload""";
        try (Connection connection = db.open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            statement.setString(2, updatedAt.toString());
            statement.setString(3, ChatMessageSerializer.messagesToJson(messages));
            statement.executeUpdate();
        } catch (Exception e) {
            log.warn("[MEMORY] 写入 SQLite 失败 session={}", sessionId, e);
        }
    }

    @Override
    public void delete(String sessionId) {
        try (Connection connection = db.open();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM memory_session WHERE session_id = ?")) {
            statement.setString(1, sessionId);
            statement.executeUpdate();
        } catch (Exception e) {
            log.warn("[MEMORY] 删除 SQLite 失败 session={}", sessionId, e);
        }
    }

    @Scheduled(cron = "0 30 4 * * *", zone = "Asia/Shanghai")
    public void purgeExpired() {
        purgeBefore(Instant.now().minus(retention));
    }

    /** 单测用：显式给截止时间，避免依赖系统时钟。 */
    void purgeBefore(Instant cutoff) {
        try (Connection connection = db.open();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM memory_session WHERE updated_at < ?")) {
            statement.setString(1, cutoff.toString());
            int removed = statement.executeUpdate();
            if (removed > 0) {
                log.info("[MEMORY] 清理 SQLite 记忆 {} 条", removed);
            }
        } catch (Exception e) {
            log.warn("[MEMORY] 清理 SQLite 失败", e);
        }
    }
}
