package com.luobo.hoyoagent.mihoyo.auth;

import com.luobo.hoyoagent.mihoyo.config.HoyoProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CredentialProvider {

    private final Credential credential;

    public CredentialProvider(@Value("${hoyo.cookie:}") String rawCookie) {
        this.credential = CookieParser.parse(rawCookie);
    }

    public Credential get() {
        return credential;
    }

    public boolean isPresent() {
        return credential != null && !credential.rawToken().isBlank();
    }
}
