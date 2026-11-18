package com.luobo.hoyoagent.agent.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SessionMemoryStorePersistenceTest {

    @TempDir
    Path tempDir;

    private FileMemoryPersistence persistence;

    private final MemoryProperties properties = new MemoryProperties(20, 200, Duration.ofHours(2));

    @BeforeEach
    void setUp() {
        persistence = new FileMemoryPersistence(tempDir.toString(), Duration.ofDays(30), new ObjectMapper());
    }

    @Test
    void 新进程能恢复上一个进程的会话() {
        SessionMemoryStore first = new SessionMemoryStore(properties, persistence);
        first.updateMessages("qqbot:c2c:x", List.<ChatMessage>of(UserMessage.from("我叫萝卜")));

        // 模拟重启：新实例、空内存、只有磁盘
        SessionMemoryStore restarted = new SessionMemoryStore(properties, persistence);

        assertThat(restarted.getMessages("qqbot:c2c:x")).hasSize(1);
    }

    @Test
    void 磁盘上过期的会话当作新的() {
        persistence.save("qqbot:c2c:y", List.<ChatMessage>of(UserMessage.from("三小时前")),
                Instant.now().minus(Duration.ofHours(3)));

        SessionMemoryStore store = new SessionMemoryStore(properties, persistence);

        assertThat(store.getMessages("qqbot:c2c:y")).isEmpty();
    }

    @Test
    void 删除会话时连磁盘一起删() {
        SessionMemoryStore store = new SessionMemoryStore(properties, persistence);
        store.updateMessages("qqbot:c2c:z", List.<ChatMessage>of(UserMessage.from("hi")));
        assertThat(persistence.load("qqbot:c2c:z")).isPresent();

        store.deleteMessages("qqbot:c2c:z");

        assertThat(persistence.load("qqbot:c2c:z")).isEmpty();
    }
}