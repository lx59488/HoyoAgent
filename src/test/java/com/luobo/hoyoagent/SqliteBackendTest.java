package com.luobo.hoyoagent;

import com.luobo.hoyoagent.agent.memory.MemoryStore;
import com.luobo.hoyoagent.agent.memory.SqliteMemoryStore;
import com.luobo.hoyoagent.agent.transcript.SqliteTranscriptStore;
import com.luobo.hoyoagent.agent.transcript.TranscriptStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "qq.enabled=false",
        "hoyo.storage.backend=sqlite",
        "hoyo.state-dir=target/test-sqlite",
        "hoyo.storage.sqlite.path=target/test-sqlite/hoyo.db",
        "hoyo.memory.dir=target/test-sqlite/memory",
        "hoyo.transcript.dir=target/test-sqlite/transcripts"
})
class SqliteBackendTest {

    @Autowired
    MemoryStore memoryStore;

    @Autowired
    TranscriptStore transcriptStore;

    @Test
    void wiresSqliteBackend() {
        assertThat(memoryStore).isInstanceOf(SqliteMemoryStore.class);
        assertThat(transcriptStore).isInstanceOf(SqliteTranscriptStore.class);
    }
}
