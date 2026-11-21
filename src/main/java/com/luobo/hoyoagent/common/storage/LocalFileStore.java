package com.luobo.hoyoagent.common.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * 本地文件持久化的唯一基座：目录解析 + 原子写 + 哈希文件名。
 *
 * <p>存在的理由不是"好看"，是这几件事原本各写了一遍：
 * <ul>
 *   <li>原子写（临时文件 + ATOMIC_MOVE，不支持时降级）—— 原来 4 份；</li>
 *   <li>sha256 前 N 位当文件名 —— 原来 3 份，长度还各不相同；</li>
 *   <li>默认目录 {@code ~/.hoyo/...} —— 原来 6 处硬编码；</li>
 *   <li>"列目录里某后缀的文件" —— 原来 4 处，各自 try/catch 打日志。</li>
 * </ul>
 * 收口的收益很具体：以后想整体换存储根、想给所有写入加 fsync、想统一加密，
 * 只改这一个类，而不是到处找。
 *
 * <p>刻意<b>不</b>做的事：
 * <ul>
 *   <li><b>不吞异常。</b>各 store 的容错口径不同（记忆读坏当没有、账号写失败仍可用），
 *       统一在这里吞掉会把"哪一层该决定"糊掉，所以写操作把 {@link IOException} 抛回去。</li>
 *   <li><b>不做锁。</b>进程内锁的粒度只有各 store 自己知道；跨进程一致性属于数据库。</li>
 *   <li><b>不解析内容。</b>它只管字节，格式版本和 DTO 映射留在各自的 store。</li>
 * </ul>
 */
public final class LocalFileStore {
    /** 所有本地状态的默认根：{@code ~/.hoyo}。 */
    private static final String DEFAULT_ROOT = ".hoyo";

    private static final String SHA_256 = "SHA-256";

    private final Path root;

    private LocalFileStore(Path root) {
        this.root = root;
    }

    /** 测试用：根目录就是它，不做任何推导。 */
    public static LocalFileStore at(Path root) {
        return new LocalFileStore(root);
    }

    /** 配置优先（非空白），否则用 {@code fallback}。 */
    public static LocalFileStore resolve(String configuredDir, Path fallback) {
        return new LocalFileStore(configuredDir == null || configuredDir.isBlank()
                ? fallback
                : Path.of(configuredDir.trim()));
    }

    /**
     * {@code hoyo.state-dir}：所有本地状态的根，默认 {@code ~/.hoyo}。
     *
     * <p>部署时（docker / k8s）把它指到挂载卷上，比逐个子目录配三遍省事；
     * 单个子目录仍可用 {@code hoyo.account-dir} / {@code hoyo.checkin.dir} / {@code hoyo.memory.dir} 覆盖。
     */
    public static Path stateRoot(String configured) {
        return configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"), DEFAULT_ROOT)
                : Path.of(configured.trim());
    }

    public Path root() {
        return root;
    }

    /** 根目录下的固定文件名（device-id / passport-device 这类）。 */
    public Path file(String name) {
        return root.resolve(name);
    }

    /** 根目录下按 key 哈希出的文件。{@code hexChars} 是十六进制位数。 */
    public Path fileOf(String key, int hexChars, String suffix) {
        return root.resolve(hashedName(key, hexChars, suffix));
    }

    /** 根目录下按 key 哈希出的子目录。 */
    public Path dirOf(String key, int hexChars) {
        return root.resolve(hashedName(key, hexChars, ""));
    }

    /** 建根目录；建不出来直接抛——只给"没有它就没法工作"的 store 调。 */
    public void ensureRoot() {
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("创建存储目录失败: " + root, e);
        }
    }

    /**
     * 把 key 变成安全的文件名：sha256 取前 {@code hexChars} 位 + 后缀。
     *
     * <p>为什么必须哈希：sessionId 里带冒号和 openid、userId 本身就是 openid，
     * 直接当文件名既会撞长度上限，也等于把平台账号写进文件系统。
     */
    public static String hashedName(String key, int hexChars, String suffix) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key 不能为空");
        }
        if (hexChars <= 0 || hexChars > 64) {
            throw new IllegalArgumentException("hexChars 必须在 1~64 之间: " + hexChars);
        }
        try {
            byte[] digest = MessageDigest.getInstance(SHA_256).digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, hexChars) + (suffix == null ? "" : suffix);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(SHA_256 + " 不可用", e);
        }
    }

    /** 原子写：临时文件 + ATOMIC_MOVE，不支持原子移动的文件系统降级为普通 move。 */
    public static void writeAtomic(Path file, String content) throws IOException {
        writeAtomic(file, content, false);
    }

    /** 同 {@link #writeAtomic}，但尽量把权限收到 0600——只给含凭据的文件用。 */
    public static void writeAtomicSecret(Path file, String content) throws IOException {
        writeAtomic(file, content, true);
    }

    private static void writeAtomic(Path file, String content, boolean ownerOnly) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        if (ownerOnly) {
            restrictPermissions(temp);
        }
        move(temp, file);
    }

    private static void move(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // 部分网络卷 / Windows 映射盘不支持原子移动，降级；这一步失败才真的抛
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

    public static String readString(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /**
     * 列目录下指定后缀的文件。
     *
     * <p>目录不存在返回空表（"没跑过"不是错误）；真正的 IOException 交给调用方打日志，
     * 因为"读目录失败"在不同 store 里的话术不一样。
     * 传 {@code ""} 当后缀等于"全部文件"。
     */
    public static List<Path> listFiles(Path dir, String suffix) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(suffix)).toList();
        }
    }


}
