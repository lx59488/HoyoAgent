package com.luobo.hoyoagent.mihoyo.credential;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 每用户一个文件的 {@link AccountStore}。
 *
 * <p>布局：
 * <pre>
 * ~/.hoyo/accounts/
 * ├── 9f3c2a1b7d4e5f60.account     ← 文件名 = sha256(userId) 前 16 位，不暴露 QQ 号/openid
 * └── 0a1b2c3d4e5f6071.account
 * </pre>
 *
 * <p>每个文件独立原子写（临时文件 + ATOMIC_MOVE），所以「改一个账号」不会重写别的账号；
 * 但也没有跨账号事务——真要事务时换 {@code JdbcAccountStore}。
 *
 * <p>明文存储，与旧 {@code credential.properties} 同级。加密接口留给后续
 * {@code CredentialCipher}（AES-256-GCM），不影响本类结构。
 */
@Component
public class FileAccountStore implements AccountStore {

    private static final Logger log = LoggerFactory.getLogger(FileAccountStore.class);

    private static final String VERSION = "1";

    private static final String SUFFIX = ".account";

    private final Path root;

    /** 单进程内串行化写；跨进程靠文件锁/数据库，当前不做 */
    private final Object writeLock = new Object();

    @Autowired
    public FileAccountStore(@Value("${hoyo.account-dir:}") String directory) {
        this(directory == null || directory.isBlank()
                ? Path.of(System.getProperty("user.home"), ".hoyo", "accounts")
                : Path.of(directory));
    }

    FileAccountStore(Path root) {
        this.root = root;
    }

    @Override
    public Optional<Account> find(String userId) {
        requireUserId(userId);
        Path file = fileOf(userId);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        Optional<Account> account = read(file);
        if (account.isPresent() && !userId.equals(account.get().userId())) {
            log.warn("账号文件内容与文件名不一致，已忽略: {}", file.getFileName());
            return Optional.empty();
        }
        return account;
    }

    @Override
    public void save(Account account) {
        requireUserId(account.userId());
        synchronized (writeLock) {
            try {
                Files.createDirectories(root);
                Path target = fileOf(account.userId());
                Path temp = target.resolveSibling(target.getFileName() + ".tmp");
                Files.writeString(temp, serialize(account), StandardCharsets.UTF_8);
                restrictPermissions(temp);
                move(temp, target);
                log.info("账号已保存: user={} uid={}", account.userId(), account.uid());
            } catch (IOException e) {
                // 和旧 CredentialStore 一致：写盘失败不让"已经登录成功"变成异常，内存里仍可用
                log.error("账号保存失败: user={} err={}", account.userId(), e.getMessage());
            }
        }
    }

    @Override
    public boolean delete(String userId) {
        requireUserId(userId);
        synchronized (writeLock) {
            try {
                return Files.deleteIfExists(fileOf(userId));
            } catch (IOException e) {
                log.error("账号删除失败: user={} err={}", userId, e.getMessage());
                return false;
            }
        }
    }

    @Override
    public List<Account> list() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<Account> accounts = new ArrayList<>();
        try (Stream<Path> files = Files.list(root)) {
            files.filter(p -> p.getFileName().toString().endsWith(SUFFIX))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(p -> read(p).ifPresent(accounts::add));
        } catch (IOException e) {
            log.warn("账号目录读取失败: {}", e.getMessage());
            return List.of();
        }
        return accounts;
    }

    private Optional<Account> read(Path file) {
        try {
            Map<String, String> kv = PropertyFile.read(file);
            String cookie = PropertyFile.orEmpty(kv.get("cookie"));
            if (cookie.isBlank()) {
                return Optional.empty();
            }
            Credential credential = new Credential(
                    CookieJar.of(cookie),
                    PropertyFile.orNull(kv.get("uid")),
                    PropertyFile.orNull(kv.get("mid")),
                    PropertyFile.orNull(kv.get("stoken")));
            String userId = PropertyFile.orNull(kv.get("user_id"));
            if (userId == null) {
                return Optional.empty();
            }
            return Optional.of(new Account(userId, credential, parseInstant(kv.get("updated_at"))));
        } catch (IOException e) {
            log.warn("账号文件读取失败: {} err={}", file.getFileName(), e.getMessage());
            return Optional.empty();
        }
    }

    private static String serialize(Account account) {
        Credential credential = account.credential();
        StringBuilder sb = new StringBuilder();
        sb.append("# hoyo agent account; 含凭据，不要提交、不要外发\n");
        sb.append("version=").append(VERSION).append('\n');
        sb.append("user_id=").append(account.userId()).append('\n');
        sb.append("uid=").append(PropertyFile.orEmpty(credential.uid())).append('\n');
        sb.append("mid=").append(PropertyFile.orEmpty(credential.mid())).append('\n');
        sb.append("stoken=").append(PropertyFile.orEmpty(credential.stoken())).append('\n');
        sb.append("cookie=").append(credential.cookieHeader()).append('\n');
        sb.append("updated_at=").append(account.updatedAt() == null ? Instant.now() : account.updatedAt()).append('\n');
        return sb.toString();
    }

    private Path fileOf(String userId) {
        return root.resolve(fileNameOf(userId));
    }

    /** 文件名用 userId 的哈希，避免把平台账号泄露在文件系统里。 */
    static String fileNameOf(String userId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(userId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16) + SUFFIX;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Instant parseInstant(String value) {
        try {
            return value == null || value.isBlank() ? Instant.EPOCH : Instant.parse(value);
        } catch (RuntimeException e) {
            return Instant.EPOCH;
        }
    }

    private static void requireUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
    }

    private static void move(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void restrictPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows 没有 POSIX 权限；用户目录继承的 ACL 已排除普通用户（管理员仍可读）
        }
    }
}
