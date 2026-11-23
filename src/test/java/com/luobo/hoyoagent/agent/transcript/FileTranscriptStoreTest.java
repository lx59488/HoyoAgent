package com.luobo.hoyoagent.agent.transcript;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileTranscriptStoreTest {

    @TempDir
    Path tempDir;

    private FileTranscriptStore store() {
        return new FileTranscriptStore(tempDir.toString(), Duration.ofDays(30), new ObjectMapper());
    }

    private static TranscriptExchange ok(String sessionId, String userId, String user, String assistant,
                                         String... tools) {
        return TranscriptExchange.ok(sessionId, userId, user, assistant, List.of(tools));
    }

    @Test
    void appendsAndReadsBackInOrder() {
        FileTranscriptStore store = store();
        store.append(ok("qqbot:c2c:u1", "qq:u1", "你好", "你好呀", "checkinToday"));
        store.append(ok("qqbot:c2c:u1", "qq:u1", "再问一次", "好的"));

        TranscriptPage page = store.read("qqbot:c2c:u1", 0, 100);

        assertThat(page.sessionId()).isEqualTo("qqbot:c2c:u1");
        assertThat(page.userId()).isEqualTo("qq:u1");
        assertThat(page.hasMore()).isFalse();
        assertThat(page.turns()).extracting(TranscriptTurn::seq).containsExactly(1L, 2L);
        assertThat(page.turns().get(0).user()).isEqualTo("你好");
        assertThat(page.turns().get(0).assistant()).isEqualTo("你好呀");
        assertThat(page.turns().get(0).tools()).containsExactly("checkinToday");
        assertThat(page.turns().get(0).at()).isNotBlank();
        assertThat(page.turns().get(0).conversationId()).isEqualTo("c2c:u1");
        assertThat(page.turns().get(0).kind()).isEqualTo("c2c");
    }

    @Test
    void readsLegacyLineWithoutConversationFields() throws IOException {
        FileTranscriptStore store = store();
        String legacy = "{\"seq\":1,\"at\":\"2026-11-23T00:00:00Z\","
                + "\"sessionId\":\"qqbot:c2c:legacy\",\"userId\":\"qq:legacy\","
                + "\"user\":\"旧问题\",\"assistant\":\"旧回答\",\"tools\":[],\"error\":null}";
        Files.writeString(store.fileOf("qqbot:c2c:legacy"), legacy + "\n", StandardCharsets.UTF_8);

        TranscriptTurn turn = store.read("qqbot:c2c:legacy", 0, 10).turns().get(0);

        assertThat(turn.conversationId()).isEqualTo("c2c:legacy");
        assertThat(turn.kind()).isEqualTo("c2c");
        assertThat(store.sessions().get(0).kind()).isEqualTo("c2c");
    }

    @Test
    void listsSessionsNewestFirst() throws InterruptedException {
        FileTranscriptStore store = store();
        store.append(ok("s1", "qq:a", "早", "早"));
        Thread.sleep(5);
        store.append(ok("s2", "qq:b", "晚", "晚"));

        List<TranscriptSession> sessions = store.sessions();

        assertThat(sessions).extracting(TranscriptSession::sessionId).containsExactly("s2", "s1");
        assertThat(sessions.get(0).userId()).isEqualTo("qq:b");
        assertThat(sessions.get(0).turns()).isEqualTo(1);
        assertThat(sessions.get(0).preview()).isEqualTo("晚");
    }

    @Test
    void pagesBackwardsBySeq() {
        FileTranscriptStore store = store();
        for (int i = 1; i <= 5; i++) {
            store.append(ok("s", "qq:u", "q" + i, "a" + i));
        }

        TranscriptPage last = store.read("s", 0, 2);
        assertThat(last.turns()).extracting(TranscriptTurn::seq).containsExactly(4L, 5L);
        assertThat(last.hasMore()).isTrue();

        TranscriptPage middle = store.read("s", 4, 2);
        assertThat(middle.turns()).extracting(TranscriptTurn::seq).containsExactly(2L, 3L);
        assertThat(middle.hasMore()).isTrue();

        TranscriptPage first = store.read("s", 2, 2);
        assertThat(first.turns()).extracting(TranscriptTurn::seq).containsExactly(1L);
        assertThat(first.hasMore()).isFalse();
    }

    @Test
    void unknownSessionReadsAsEmpty() {
        FileTranscriptStore store = store();

        assertThat(store.read("nope", 0, 10).turns()).isEmpty();
        assertThat(store.sessions()).isEmpty();
    }

    @Test
    void skipsMalformedLines() throws IOException {
        FileTranscriptStore store = store();
        store.append(ok("s", "qq:u", "第一句", "回复一"));
        Files.writeString(store.fileOf("s"), "{oops\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        store.append(ok("s", "qq:u", "第二句", "回复二"));

        assertThat(store.read("s", 0, 10).turns())
                .extracting(TranscriptTurn::seq)
                .containsExactly(1L, 2L);
    }

    @Test
    void recordsFailedTurnWithoutAssistant() {
        FileTranscriptStore store = store();
        store.append(TranscriptExchange.failed("s", "qq:u", "在吗", List.of("checkinToday"), "boom"));

        TranscriptTurn turn = store.read("s", 0, 10).turns().get(0);

        assertThat(turn.assistant()).isNull();
        assertThat(turn.error()).isEqualTo("boom");
        assertThat(turn.tools()).containsExactly("checkinToday");
    }

    @Test
    void purgeDeletesOnlyExpiredFiles() throws IOException {
        FileTranscriptStore store = store();
        store.append(ok("old", "qq:a", "旧", "旧"));
        store.append(ok("fresh", "qq:b", "新", "新"));

        Path oldFile = store.fileOf("old");
        Files.setLastModifiedTime(oldFile, FileTime.from(Instant.now().minus(Duration.ofDays(60))));
        store.purgeExpired();

        assertThat(Files.exists(oldFile)).isFalse();
        assertThat(Files.exists(store.fileOf("fresh"))).isTrue();
        assertThat(store.sessions()).extracting(TranscriptSession::sessionId).containsExactly("fresh");
    }

    @Test
    void disabledStoreWritesNothingAndCreatesNoDirectory() {
        Path dir = tempDir.resolve("off");
        FileTranscriptStore store = new FileTranscriptStore(dir.toString(), Duration.ofDays(30), false,
                new ObjectMapper());

        store.append(ok("s", "qq:u", "hi", "hello"));

        assertThat(Files.exists(dir)).isFalse();
        assertThat(store.sessions()).isEmpty();
        assertThat(store.read("s", 0, 10).turns()).isEmpty();
    }

    @Test
    void rebuildsIndexAndContinuesSeqAfterRestart() {
        FileTranscriptStore first = store();
        first.append(ok("s", "qq:u", "一", "1"));
        first.append(ok("s", "qq:u", "二", "2"));

        FileTranscriptStore second = store();
        assertThat(second.sessions()).extracting(TranscriptSession::turns).containsExactly(2);
        second.append(ok("s", "qq:u", "三", "3"));

        assertThat(second.read("s", 0, 10).turns())
                .extracting(TranscriptTurn::seq)
                .containsExactly(1L, 2L, 3L);
    }

    @Test
    void ignoresBlankSession() {
        FileTranscriptStore store = store();
        store.append(ok("  ", "qq:u", "hi", "hello"));

        assertThat(store.sessions()).isEmpty();
    }

    @Test
    void previewIsOneLineAndTruncated() {
        FileTranscriptStore store = store();
        store.append(ok("s", "qq:u", "第一行\n第二行 " + "x".repeat(60), "a"));

        String preview = store.sessions().get(0).preview();

        assertThat(preview).doesNotContain("\n");
        assertThat(preview).endsWith("…");
        assertThat(preview).hasSize(41);
    }
}
