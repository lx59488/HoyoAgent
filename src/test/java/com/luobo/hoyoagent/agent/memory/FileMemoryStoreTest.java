package com.luobo.hoyoagent.agent.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileMemoryStoreTest {

    @TempDir
    Path tempDir;

    private FileMemoryStore persistence() {
        return new FileMemoryStore(tempDir.toString(), Duration.ofDays(30), new ObjectMapper());
    }

    @Test
    void roundTripKeepsMessages() {
        FileMemoryStore p = persistence();
        List<ChatMessage> messages = List.of(UserMessage.from("我叫萝卜"), UserMessage.from("第二句"));

        p.save("qqbot:c2c:x", messages, Instant.now());

        assertThat(p.load("qqbot:c2c:x").orElseThrow().messages()).isEqualTo(messages);
    }

    @Test
    void systemSummarySurvivesRoundTrip() {
        FileMemoryStore p = persistence();
        List<ChatMessage> messages = List.of(
                SystemMessage.from("【更早对话摘要】用户喜欢原神，上周问过深渊"),
                UserMessage.from("继续"));

        p.save("s1", messages, Instant.now());

        List<ChatMessage> loaded = p.load("s1").orElseThrow().messages();
        assertThat(loaded).hasSize(2);
        assertThat(loaded.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(((SystemMessage) loaded.get(0)).text())
                .isEqualTo("【更早对话摘要】用户喜欢原神，上周问过深渊");
    }

    @Test
    void corruptFileCountsAsEmpty() throws IOException {
        FileMemoryStore p = persistence();
        Files.writeString(p.fileOf("s1"), "{ 这不是 json", StandardCharsets.UTF_8);

        assertThat(p.load("s1")).isEmpty();
    }

    @Test
    void unknownVersionCountsAsEmpty() throws IOException {
        FileMemoryStore p = persistence();
        Files.writeString(p.fileOf("s1"),
                "{\"v\":99,\"updatedAt\":\"2026-01-01T00:00:00Z\",\"messages\":[]}",
                StandardCharsets.UTF_8);

        assertThat(p.load("s1")).isEmpty();
    }

    @Test
    void deleteRemovesFile() {
        FileMemoryStore p = persistence();
        p.save("s1", List.of(UserMessage.from("hi")), Instant.now());

        p.delete("s1");

        assertThat(p.load("s1")).isEmpty();
    }

    @Test
    void purgeRemovesOnlyOldFiles() throws IOException {
        FileMemoryStore p = persistence();
        p.save("old", List.of(UserMessage.from("old")), Instant.now());
        p.save("new", List.of(UserMessage.from("new")), Instant.now());
        Files.setLastModifiedTime(p.fileOf("old"),
                FileTime.from(Instant.now().minus(Duration.ofDays(40))));

        p.purgeExpired();

        assertThat(p.load("old")).isEmpty();
        assertThat(p.load("new")).isPresent();
    }
}