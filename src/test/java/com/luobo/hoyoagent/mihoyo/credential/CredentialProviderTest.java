package com.luobo.hoyoagent.mihoyo.credential;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CredentialProviderTest {

    @TempDir
    Path tempDir;

    private FileAccountStore store() {
        return new FileAccountStore(tempDir.resolve("accounts"));
    }

    private LegacyCredentialFile legacy(String cookie) throws IOException {
        Path file = tempDir.resolve("credential.properties");
        Files.writeString(file, "cookie=" + cookie + "\n");
        return new LegacyCredentialFile(file);
    }

    @Test
    void storeWinsOverEnvAndLegacy() throws IOException {
        FileAccountStore store = store();
        store.save(new Account("local", Credential.of("stoken=from-store; mid=m; stuid=1"), Instant.now()));

        CredentialProvider provider = new CredentialProvider(
                store, legacy("stoken=from-legacy; mid=m; stuid=2"), "local", "stoken=from-env; mid=m; stuid=3");

        assertThat(provider.get().stoken()).isEqualTo("from-store");
    }

    @Test
    void legacyIsReadOnlyFallback() throws IOException {
        CredentialProvider provider = new CredentialProvider(
                store(), legacy("stoken=from-legacy; mid=m; stuid=2"), "local", "");

        assertThat(provider.get().stoken()).isEqualTo("from-legacy");
        assertThat(provider.get().uid()).isEqualTo("2");
    }

    @Test
    void envIsLastResort() throws IOException {
        CredentialProvider provider = new CredentialProvider(
                store(), new LegacyCredentialFile(tempDir.resolve("none.properties")), "local",
                "ltoken=env; ltuid=9");

        assertThat(provider.get().uid()).isEqualTo("9");
        assertThat(provider.get().hasStoken()).isFalse();
    }

    @Test
    void updatePersistsToStore() {
        FileAccountStore store = store();
        CredentialProvider provider = new CredentialProvider(
                store, new LegacyCredentialFile(tempDir.resolve("none.properties")), "local", "");

        provider.update(Credential.of("stoken=fresh; mid=m; stuid=5"));

        assertThat(provider.get().stoken()).isEqualTo("fresh");
        assertThat(store.find("local").orElseThrow().credential().stoken()).isEqualTo("fresh");
    }
}
