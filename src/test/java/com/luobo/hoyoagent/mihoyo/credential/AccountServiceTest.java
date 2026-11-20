package com.luobo.hoyoagent.mihoyo.credential;

import com.luobo.hoyoagent.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountServiceTest {

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

    private LegacyCredentialFile none() {
        return new LegacyCredentialFile(tempDir.resolve("none.properties"));
    }

    @Test
    void storeWinsOverLegacyAndEnv() throws IOException {
        FileAccountStore store = store();
        store.save(new Account("local", Credential.of("stoken=from-store; mid=m; stuid=1"), Instant.now()));
        AccountService service = new AccountService(store,
                legacy("stoken=from-legacy; mid=m; stuid=2"), "local",
                "stoken=from-env; mid=m; stuid=3");

        assertThat(service.find("local").orElseThrow().stoken()).isEqualTo("from-store");
    }

    @Test
    void legacyAndEnvOnlyApplyToLocalUser() throws IOException {
        AccountService service = new AccountService(store(), legacy("ltoken=legacy; ltuid=7"), "local", "");

        assertThat(service.find("local").orElseThrow().uid()).isEqualTo("7");
        assertThat(service.find("qq:E43830A902F2")).isEmpty();
    }

    @Test
    void requireThrowsWhenUnbound() {
        AccountService service = new AccountService(store(), none(), "local", "");

        assertThatThrownBy(() -> service.require("qq:someone"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("绑定");
    }

    @Test
    void saveTakesEffectImmediately() {
        AccountService service = new AccountService(store(), none(), "local", "");
        assertThat(service.isBound("qq:someone")).isFalse();

        service.save("qq:someone", Credential.of("ltoken=a; ltuid=42"));

        assertThat(service.isBound("qq:someone")).isTrue();
        assertThat(service.require("qq:someone").uid()).isEqualTo("42");
        assertThat(service.findByUid("42")).isPresent();
    }

    @Test
    void deleteRemovesAccount() {
        AccountService service = new AccountService(store(), none(), "local", "");
        service.save("qq:someone", Credential.of("ltoken=a; ltuid=42"));

        assertThat(service.delete("qq:someone")).isTrue();
        assertThat(service.isBound("qq:someone")).isFalse();
        assertThat(service.all()).isEmpty();
    }
}