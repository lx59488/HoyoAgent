package com.luobo.hoyoagent.agent.transcript;

import com.luobo.hoyoagent.common.storage.LocalFileStore;
import com.luobo.hoyoagent.common.storage.SqliteProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteTranscriptStoreTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    private SqliteTranscriptStore store(String dbName) {
        return store(dbName, true);
    }

    private SqliteTranscriptStore store(String dbName, boolean enabled) {
        SqliteProperties sqlite = new SqliteProperties(
                tempDir.resolve(dbName + ".db").toString(), Duration.ofSeconds(5));
        TranscriptProperties properties = new TranscriptProperties(
                enabled, tempDir.resolve("transcripts").toString(), Duration.ofDays(90));
        return new SqliteTranscriptStore(sqlite, tempDir.toString(), mapper, properties);
    }

    @Test
    void appendsAndReadsTurnsInOrder() {
        SqliteTranscriptStore store = store("tx");

        store.append(TranscriptExchange.ok("s1", "u1", "first", "answer-1", List.of("checkinToday")));
        store.append(TranscriptExchange.ok("s1", "u1", "second", "answer-2", List.of()));

        TranscriptPage page = store.read("s1", 0, 10);
        assertThat(page.userId()).isEqualTo("u1");
        assertThat(page.turns()).extracting(TranscriptTurn::seq).containsExactly(1L, 2L);
        assertThat(page.turns()).extracting(TranscriptTurn::user).containsExactly("first", "second");
        assertThat(page.turns().get(0).tools()).containsExactly("checkinToday");
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void listsSessionsNewestFirst() throws InterruptedException {
        SqliteTranscriptStore store = store("tx");

        store.append(TranscriptExchange.ok("s1", "u1", "one", "a", List.of()));
        Thread.sleep(5);
        store.append(TranscriptExchange.ok("s2", "u2", "two", "b", List.of()));

        assertThat(store.sessions())
                .extracting(TranscriptSession::sessionId)
                .containsExactly("s2", "s1");
    }

    @Test
    void paginatesBySeq() {
        SqliteTranscriptStore store = store("tx");
        store.append(TranscriptExchange.ok("s1", "u1", "one", "a", List.of()));
        store.append(TranscriptExchange.ok("s1", "u1", "two", "b", List.of()));
        store.append(TranscriptExchange.ok("s1", "u1", "three", "c", List.of()));

        TranscriptPage latest = store.read("s1", 0, 2);
        assertThat(latest.turns()).extracting(TranscriptTurn::seq).containsExactly(2L, 3L);
        assertThat(latest.hasMore()).isTrue();

        TranscriptPage older = store.read("s1", 2, 2);
        assertThat(older.turns()).extracting(TranscriptTurn::seq).containsExactly(1L);
        assertThat(older.hasMore()).isFalse();
    }

    @Test
    void unknownSessionReturnsEmpty() {
        TranscriptPage page = store("tx").read("missing", 0, 10);

        assertThat(page.turns()).isEmpty();
        assertThat(page.hasMore()).isFalse();
        assertThat(page.userId()).isEmpty();
    }

    @Test
    void disabledStoreIgnoresAppends() {
        SqliteTranscriptStore store = store("tx", false);

        store.append(TranscriptExchange.ok("s1", "u1", "hello", "hi", List.of()));

        assertThat(store.sessions()).isEmpty();
        assertThat(store.read("s1", 0, 10).turns()).isEmpty();
    }

    @Test
    void purgeBeforeRemovesOldTurns() {
        SqliteTranscriptStore store = store("tx");
        store.append(TranscriptExchange.ok("s1", "u1", "hello", "hi", List.of()));

        store.purgeBefore(Instant.now().plusSeconds(1));

        assertThat(store.sessions()).isEmpty();
        assertThat(store.read("s1", 0, 10).turns()).isEmpty();
    }

    @Test
    void migratesLegacyJsonlOnce() throws Exception {
        Path transcriptDir = tempDir.resolve("transcripts");
        Files.createDirectories(transcriptDir);
        Path legacyFile = LocalFileStore.at(transcriptDir).fileOf("legacy-session", 32, ".jsonl");
        String legacyLine = "{\"seq\":1,\"at\":\"2026-11-23T00:00:00Z\","
                + "\"sessionId\":\"qqbot:c2c:legacy-session\",\"userId\":\"qq:u9\","
                + "\"user\":\"旧问题\",\"assistant\":\"旧回答\",\"tools\":[\"tool\"],\"error\":null}\n";
        Files.writeString(legacyFile, legacyLine, StandardCharsets.UTF_8);

        SqliteTranscriptStore store = store("tx");
        store.migrateFromLegacy();

        assertThat(store.sessions()).extracting(TranscriptSession::sessionId)
                .containsExactly("qqbot:c2c:legacy-session");
        assertThat(store.sessions().get(0).kind()).isEqualTo("c2c");
        assertThat(store.sessions().get(0).conversationId()).isEqualTo("c2c:legacy-session");
        TranscriptPage page = store.read("qqbot:c2c:legacy-session", 0, 10);
        assertThat(page.turns()).hasSize(1);
        assertThat(page.turns().get(0).user()).isEqualTo("旧问题");
        assertThat(page.turns().get(0).kind()).isEqualTo("c2c");

        // 再启动一次不应该重复导入
        store.migrateFromLegacy();
        assertThat(store.read("qqbot:c2c:legacy-session", 0, 10).turns()).hasSize(1);
    }

    @Test
    void reopenedStoreContinuesSequence() {
        SqliteTranscriptStore first = store("tx");
        first.append(TranscriptExchange.ok("s1", "u1", "one", "a", List.of()));

        SqliteTranscriptStore second = store("tx");
        second.append(TranscriptExchange.ok("s1", "u1", "two", "b", List.of()));

        assertThat(second.read("s1", 0, 10).turns())
                .extracting(TranscriptTurn::seq)
                .containsExactly(1L, 2L);
    }
}
