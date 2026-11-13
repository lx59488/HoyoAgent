package com.luobo.hoyoagent.mihoyo.credential;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 当前进程使用的凭据。
 *
 * <p>来源优先级（单用户阶段）：
 * <ol>
 *   <li>{@link AccountStore} 里该 {@code userId} 的账号（带 stoken 的优先）</li>
 *   <li>旧 {@code ~/.hoyo/credential.properties}（只读兜底）</li>
 *   <li>{@code .env} 的 {@code HOYO_COOKIE}</li>
 * </ol>
 *
 * <p>为什么文件优先：手填的 cookie 通常只有 ltoken，而签到用的是 cookie_token、查战绩要 v2。
 * 让 {@code .env} 永远优先会导致"扫码一次、重启后又退回旧凭据"。
 *
 * <p>多用户阶段这里会被换成"按 userId 解析"，而不是单例持有当前账号。
 */
@Component
public class CredentialProvider {

    private static final Logger log = LoggerFactory.getLogger(CredentialProvider.class);

    private final AccountStore store;

    private final String userId;

    private volatile Credential credential;

    private volatile String source;

    public CredentialProvider(AccountStore store,
                              LegacyCredentialFile legacyFile,
                              @Value("${hoyo.user-id:local}") String userId,
                              @Value("${hoyo.cookie:}") String rawCookie) {
        this.store = store;
        this.userId = userId;

        Account stored = store.find(userId).orElse(null);
        Credential legacy = legacyFile.read().orElse(null);
        Credential env = Credential.of(rawCookie);

        if (stored != null && stored.credential().hasStoken()) {
            this.credential = stored.credential();
            this.source = "store";
        } else if (legacy != null && legacy.hasStoken()) {
            this.credential = legacy;
            this.source = "legacy";
        } else if (!env.cookieHeader().isBlank()) {
            this.credential = env;
            this.source = "env";
        } else if (stored != null) {
            this.credential = stored.credential();
            this.source = "store(no-stoken)";
        } else if (legacy != null) {
            this.credential = legacy;
            this.source = "legacy(no-stoken)";
        } else {
            this.credential = env;
            this.source = "none";
        }

        // 只打印来源与"有没有"，永远不打印凭据本身
        log.info("米游社凭据: user={} source={} uid={} ltoken={} stoken={}",
                userId,
                source,
                this.credential.uid(),
                this.credential.cookieHeader().contains("ltoken") ? "present" : "absent",
                this.credential.hasStoken() ? "present" : "absent");
    }

    public Credential get() {
        return credential;
    }

    public boolean isPresent() {
        return credential != null && !credential.cookieHeader().isBlank();
    }

    public String userId() {
        return userId;
    }

    /** 当前凭据来源，调试用（store / legacy / env / qr-login …）。 */
    public String source() {
        return source;
    }

    /** 扫码登录 / v2 刷新成功后调用：先换内存，再落盘。 */
    public void update(Credential fresh) {
        this.credential = fresh;
        this.source = "qr-login";
        this.store.save(new Account(userId, fresh, Instant.now()));
    }
}
