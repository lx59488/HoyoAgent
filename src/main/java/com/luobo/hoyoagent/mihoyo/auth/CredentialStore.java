package com.luobo.hoyoagent.mihoyo.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 扫码登录拿到的凭据落盘到 {@code ~/.hoyo/credential.properties}。
 *
 * <p><b>这一版是明文存储</b>，和 {@code .env} 里手填 cookie 的安全级别一样：文件不进仓库、
 * 权限尽量收成 owner-only。加密（AES-256-GCM）留到多用户阶段再做——现在只有一个人一个账号，
 * 过早引入密钥管理只会增加"密钥丢了数据全废"的风险。
 */
@Component
public class CredentialStore {

    private static final Logger log = LoggerFactory.getLogger(CredentialStore.class);

    private static final Path DEFAULT_FILE = Path.of(System.getProperty("user.home"), ".hoyo", "credential.properties");

    private final Path file;

    public CredentialStore() {
        this(DEFAULT_FILE);
    }

    CredentialStore(Path file) {
        this.file = file;
    }

    public Optional<Credential> load() {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            Map<String, String> kv = parseLines(Files.readAllLines(file, StandardCharsets.UTF_8));
            String cookie = kv.getOrDefault("cookie", "");
            if (cookie.isBlank()) {
                return Optional.empty();
            }
            Credential parsed = CookieParser.parse(cookie);
            return Optional.of(new Credential(
                    cookie,
                    kv.getOrDefault("uid", parsed.uid()),
                    kv.getOrDefault("mid", parsed.mid()),
                    kv.getOrDefault("stoken", parsed.stoken())));
        } catch (IOException e) {
            log.warn("读取凭据文件失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public void save(Credential credential) {
        Map<String, String> kv = new LinkedHashMap<>();
        kv.put("cookie", credential.cookieHeader());
        kv.put("uid", credential.uid() == null ? "" : credential.uid());
        kv.put("mid", credential.mid() == null ? "" : credential.mid());
        kv.put("stoken", credential.stoken() == null ? "" : credential.stoken());
        kv.put("updated_at", Instant.now().toString());

        StringBuilder sb = new StringBuilder();
        sb.append("# 由 /api/auth/qr 扫码登录写入，含凭据，不要提交、不要外发\n");
        kv.forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));

        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            restrictPermissions(tmp);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("凭据已写入 {}（uid={}, stoken={}）", file, credential.uid(),
                    credential.hasStoken() ? "present" : "absent");
        } catch (IOException e) {
            // 写盘失败不该让"已经登录成功"变成异常，内存里仍然可用
            log.error("凭据写入失败: {}", e.getMessage());
        }
    }

    private void restrictPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows 或非 POSIX 文件系统：靠用户目录权限兜底
        }
    }

    private static Map<String, String> parseLines(List<String> lines) {
        Map<String, String> kv = new LinkedHashMap<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int i = trimmed.indexOf('=');
            if (i > 0) {
                kv.put(trimmed.substring(0, i).trim(), trimmed.substring(i + 1).trim());
            }
        }
        return kv;
    }
}
