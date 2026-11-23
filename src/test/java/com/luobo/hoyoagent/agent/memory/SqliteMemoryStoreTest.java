package com.luobo.hoyoagent.agent.memory;

import com.luobo.hoyoagent.common.storage.SqliteProperties;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteMemoryStoreTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    private SqliteMemoryStore store(String dbName) {
        SqliteProperties properties = new SqliteProperties(
                tempDir.resolve(dbName + ".db").toString(), Duration.ofSeconds(5));
        return new SqliteMemoryStore(
                properties,
                tempDir.toString(),
                tempDir.resolve("memory").toString(),
                Duration.ofDays(30),
                mapper);
    }

    @Test
    void savesAndLoadsMessages() {
        SqliteMemoryStore store = store("memory");

        store.save("session-1",
                List.of(UserMessage.from("你好"), AiMessage.from("你好，我是助手")),
                Instant.parse("2026-11-23T00:00:00Z"));

        Optional<MemoryStore.Stored> loaded = store.load("session-1");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().updatedAt()).isEqualTo(Instant.parse("2026-11-23T00:00:00Z"));
        assertThat(loaded.get().messages()).hasSize(2);
        assertThat(((UserMessage) loaded.get().messages().get(0)).singleText()).isEqualTo("你好");
        assertThat(((AiMessage) loaded.get().messages().get(1)).text()).isEqualTo("你好，我是助手");
    }

    @Test
    void updateReplacesWholeSession() {
        SqliteMemoryStore store = store("memory");

        store.save("session-1", List.of(UserMessage.from("第一轮")), Instant.now());
        store.save("session-1", List.of(UserMessage.from("第二轮"), AiMessage.from("回答")), Instant.now());

        assertThat(store.load("session-1").orElseThrow().messages()).hasSize(2);
    }

    @Test
    void deleteRemovesSession() {
        SqliteMemoryStore store = store("memory");
        store.save("session-1", List.of(UserMessage.from("hi")), Instant.now());

        store.delete("session-1");

        assertThat(store.load("session-1")).isEmpty();
    }

    @Test
    void reloadsFromSameDatabaseFile() {
        SqliteMemoryStore first = store("memory");
        first.save("session-1", List.of(UserMessage.from("persisted")), Instant.now());

        SqliteMemoryStore second = store("memory");

        assertThat(second.load("session-1").orElseThrow().messages()).hasSize(1);
    }

    @Test
    void migratesLegacyFileOnFirstRead() {
        Path memoryDir = tempDir.resolve("memory");
        FileMemoryStore fileStore = new FileMemoryStore(memoryDir.toString(), Duration.ofDays(30), mapper);
        fileStore.save("legacy-session",
                List.of(UserMessage.from("旧记忆"), AiMessage.from("旧回答")), Instant.now());

        SqliteMemoryStore store = store("memory");

        Optional<MemoryStore.Stored> loaded = store.load("legacy-session");
        assertThat(loaded).isPresent();
        assertThat(((UserMessage) loaded.get().messages().get(0)).singleText()).isEqualTo("旧记忆");
        // 迁移后再读一次，走的是 SQLite 而不是旧文件
        assertThat(store.load("legacy-session")).isPresent();
    }

    @Test
    void purgeBeforeRemovesOldSessions() {
        SqliteMemoryStore store = store("memory");
        store.save("old", List.of(UserMessage.from("old")), Instant.parse("2026-01-01T00:00:00Z"));
        store.save("fresh", List.of(UserMessage.from("fresh")), Instant.parse("2026-06-01T00:00:00Z"));

        store.purgeBefore(Instant.parse("2026-03-01T00:00:00Z"));

        assertThat(store.load("old")).isEmpty();
        assertThat(store.load("fresh")).isPresent();
    }

    @Test
    void unknownSessionReturnsEmpty() {
        assertThat(store("memory").load("missing")).isEmpty();
    }
}
