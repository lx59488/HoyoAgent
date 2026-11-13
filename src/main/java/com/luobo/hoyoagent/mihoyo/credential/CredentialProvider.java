package com.luobo.hoyoagent.mihoyo.credential;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 凭据来源：扫码写入的文件优先（它带 stoken），否则回落到 {@code .env} 里手填的 cookie。
 *
 * <p>优先级不是随手定的：手填的 cookie 通常只有 ltoken，而签到用的是 cookie_token
 * （查战绩还需要 v2）。如果反过来让 {@code .env} 永远优先，用户扫码一次、重启后又退回旧凭据，
 * 会非常难受。
 */
@Component
public class CredentialProvider {

    private static final Logger log = LoggerFactory.getLogger(CredentialProvider.class);

    private final CredentialStore store;

    private volatile Credential credential;

    private volatile String source;

    public CredentialProvider(@Value("${hoyo.cookie:}") String rawCookie, CredentialStore store) {
        this.store = store;
        Credential fromEnv = Credential.of(rawCookie);
        Credential fromFile = store.load().orElse(null);

        if (fromFile != null && fromFile.hasStoken()) {
            this.credential = fromFile;
            this.source = "file";
        } else if (!fromEnv.cookieHeader().isBlank()) {
            this.credential = fromEnv;
            this.source = "env";
        } else if (fromFile != null) {
            this.credential = fromFile;
            this.source = "file(no-stoken)";
        } else {
            this.credential = fromEnv;
            this.source = "none";
        }

        // 只打印来源与"有没有"，永远不打印凭据本身
        log.info("米游社凭据: source={} uid={} ltoken={} stoken={}",
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

    /** 扫码登录成功后调用：先换内存，再落盘。 */
    public void update(Credential fresh) {
        this.credential = fresh;
        this.source = "qr-login";
        this.store.save(fresh);
    }
}
