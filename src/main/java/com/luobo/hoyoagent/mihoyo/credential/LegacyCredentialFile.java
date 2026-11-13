package com.luobo.hoyoagent.mihoyo.credential;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * 旧的单文件凭据（{@code ~/.hoyo/credential.properties}）的只读兜底。
 *
 * <p>只读、不改、不删：这样从旧版本升级时不需要重新扫码。
 * 一旦发生登录/刷新，{@link AccountStore} 会写入新目录；旧文件可手动删除。
 */
@Component
public class LegacyCredentialFile {

    private static final Logger log = LoggerFactory.getLogger(LegacyCredentialFile.class);

    private static final Path DEFAULT_FILE =
            Path.of(System.getProperty("user.home"), ".hoyo", "credential.properties");

    private final Path file;

    public LegacyCredentialFile() {
        this(DEFAULT_FILE);
    }

    LegacyCredentialFile(Path file) {
        this.file = file;
    }

    public Optional<Credential> read() {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            Map<String, String> kv = PropertyFile.read(file);
            String cookie = PropertyFile.orEmpty(kv.get("cookie"));
            if (cookie.isBlank()) {
                return Optional.empty();
            }
            Credential parsed = Credential.of(cookie);
            return Optional.of(new Credential(
                    parsed.cookies(),
                    firstPresent(kv.get("uid"), parsed.uid()),
                    firstPresent(kv.get("mid"), parsed.mid()),
                    firstPresent(kv.get("stoken"), parsed.stoken())));
        } catch (IOException e) {
            log.warn("旧凭据文件读取失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static String firstPresent(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
