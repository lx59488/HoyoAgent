package com.luobo.hoyoagent.mihoyo.credential;

import com.luobo.hoyoagent.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    private AccountService service() {
        return new AccountService(store());
    }

    @Test
    void requireThrowsWhenUnbound() {
        AccountService service = service();

        assertThatThrownBy(() -> service.requireCredential("qq:someone"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("绑定");
    }

    @Test
    void credentialOfIsEmptyForUnknownOrBlankUser() {
        AccountService service = service();

        assertThat(service.credentialOf("qq:someone")).isEmpty();
        assertThat(service.credentialOf("")).isEmpty();
        assertThat(service.credentialOf(null)).isEmpty();
    }

    @Test
    void storedAccountIsReadableWithoutRestart() {
        FileAccountStore store = store();
        store.save(new Account("qq:someone", Credential.of("ltoken=a; ltuid=42"), Instant.now()));

        AccountService service = new AccountService(store);

        assertThat(service.credentialOf("qq:someone").orElseThrow().uid()).isEqualTo("42");
    }

    @Test
    void saveTakesEffectImmediately() {
        AccountService service = service();
        assertThat(service.isBound("qq:someone")).isFalse();

        service.save("qq:someone", Credential.of("ltoken=a; ltuid=42"));

        assertThat(service.isBound("qq:someone")).isTrue();
        assertThat(service.requireCredential("qq:someone").uid()).isEqualTo("42");
        assertThat(service.findByUid("42")).isPresent();
    }

    @Test
    void deleteRemovesAccountAndCache() {
        AccountService service = service();
        service.save("qq:someone", Credential.of("ltoken=a; ltuid=42"));
        assertThat(service.credentialOf("qq:someone")).isPresent();

        assertThat(service.delete("qq:someone")).isTrue();
        assertThat(service.isBound("qq:someone")).isFalse();
        assertThat(service.credentialOf("qq:someone")).isEmpty();
        assertThat(service.all()).isEmpty();
    }

    @Test
    void claimRejectsUidOwnedByAnotherUser() {
        AccountService service = service();
        service.save("qq:A", Credential.of("ltoken=a; ltuid=42"));

        assertThatThrownBy(() -> service.claim("qq:B", Credential.of("ltoken=b; ltuid=42")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("其他用户");
        assertThat(service.credentialOf("qq:B")).isEmpty();
    }

    @Test
    void claimAllowsSameUserToRebind() {
        AccountService service = service();
        service.save("qq:A", Credential.of("stoken=old; mid=m; stuid=42"));

        service.claim("qq:A", Credential.of("stoken=new; mid=m; stuid=42"));

        assertThat(service.credentialOf("qq:A").orElseThrow().stoken()).isEqualTo("new");
    }
}