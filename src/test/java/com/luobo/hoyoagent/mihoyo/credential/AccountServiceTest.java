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

        assertThat(service.credentialOf("local").orElseThrow().stoken()).isEqualTo("from-store");
    }

    @Test
    void legacyAndEnvOnlyApplyToLocalUser() throws IOException {
        AccountService service = new AccountService(store(), legacy("ltoken=legacy; ltuid=7"), "local", "");

        assertThat(service.credentialOf("local").orElseThrow().uid()).isEqualTo("7");
        assertThat(service.credentialOf("qq:E43830A902F2")).isEmpty();
    }

    @Test
    void requireThrowsWhenUnbound() {
        AccountService service = new AccountService(store(), none(), "local", "");

        assertThatThrownBy(() -> service.requireCredential("qq:someone"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("绑定");
    }

    @Test
    void saveTakesEffectImmediately() {
        AccountService service = new AccountService(store(), none(), "local", "");
        assertThat(service.isBound("qq:someone")).isFalse();

        service.save("qq:someone", Credential.of("ltoken=a; ltuid=42"));

        assertThat(service.isBound("qq:someone")).isTrue();
        assertThat(service.requireCredential("qq:someone").uid()).isEqualTo("42");
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

    @Test
    void claimRejectsUidOwnedByAnotherUser() {
        AccountService service = new AccountService(store(), none(), "local", "");
        service.save("qq:A", Credential.of("ltoken=a; ltuid=42"));

        assertThatThrownBy(() -> service.claim("qq:B", Credential.of("ltoken=b; ltuid=42")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("其他用户");
        assertThat(service.credentialOf("qq:B")).isEmpty();
    }

    @Test
    void claimAllowsSameUserToRebind() {
        AccountService service = new AccountService(store(), none(), "local", "");
        service.save("qq:A", Credential.of("stoken=old; mid=m; stuid=42"));

        service.claim("qq:A", Credential.of("stoken=new; mid=m; stuid=42"));

        assertThat(service.credentialOf("qq:A").orElseThrow().stoken()).isEqualTo("new");
    }

    /**
     * 来源要跟着"最近一次写入"走，而不是启动时的快照。
     *
     * <p>这条是 2026-11-20 删 {@code CredentialProvider} 的回归用例：
     * 那个类在启动时拍了一份凭据快照，扫码换绑后 {@code /api/auth/status} 还会报旧 uid。
     */
    @Test
    void sourceFollowsTheMostRecentWrite() throws IOException {
        AccountService service = new AccountService(store(),
                legacy("ltoken=legacy; ltuid=7"), "local", "");

        assertThat(service.sourceOf("local")).isEqualTo(AccountService.Source.LEGACY);

        service.save("local", Credential.of("ltoken=fresh; ltuid=42"));

        assertThat(service.sourceOf("local")).isEqualTo(AccountService.Source.ACCOUNT);
        assertThat(service.credentialOf("local").orElseThrow().uid()).isEqualTo("42");
    }

    @Test
    void envIsTheLastResortAndOnlyForLocalUser() {
        AccountService service = new AccountService(store(), none(), "local", "ltoken=env; ltuid=9");

        assertThat(service.sourceOf("local")).isEqualTo(AccountService.Source.ENV);
        assertThat(service.sourceOf("qq:someone")).isEqualTo(AccountService.Source.NONE);
        assertThat(service.sourceOf(null)).isEqualTo(AccountService.Source.NONE);
    }
}