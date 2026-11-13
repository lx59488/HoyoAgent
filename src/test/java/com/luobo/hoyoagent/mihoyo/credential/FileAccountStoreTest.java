package com.luobo.hoyoagent.mihoyo.credential;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileAccountStoreTest {

    @TempDir
    Path tempDir;

    private FileAccountStore store;

    @BeforeEach
    void setUp() {
        store = new FileAccountStore(tempDir);
    }

    @Test
    void savesFindsListsAndDeletes() {
        store.save(new Account("local", Credential.of("stoken=s1; mid=m1; stuid=100"), Instant.parse("2026-11-13T00:00:00Z")));

        assertThat(store.find("local")).isPresent();
        assertThat(store.find("local").orElseThrow().credential().stoken()).isEqualTo("s1");
        assertThat(store.list()).hasSize(1);

        assertThat(store.delete("local")).isTrue();
        assertThat(store.find("local")).isEmpty();
        assertThat(store.delete("local")).isFalse();
    }

    @Test
    void fileNameDoesNotLeakUserId() {
        String fileName = FileAccountStore.fileNameOf("123456789");

        assertThat(fileName).endsWith(".account");
        assertThat(fileName).doesNotContain("123456789");
        assertThat(fileName).hasSize(16 + ".account".length());
    }

    @Test
    void saveOverwritesSameUser() {
        store.save(new Account("local", Credential.of("stoken=old; mid=m; stuid=1"), Instant.now()));
        store.save(new Account("local", Credential.of("stoken=new; mid=m; stuid=1"), Instant.now()));

        assertThat(store.list()).hasSize(1);
        assertThat(store.find("local").orElseThrow().credential().stoken()).isEqualTo("new");
    }

    @Test
    void corruptFileIsIgnored() throws IOException {
        Files.writeString(tempDir.resolve("broken.account"), "this is not a property file");

        assertThat(store.list()).isEmpty();
    }
}
