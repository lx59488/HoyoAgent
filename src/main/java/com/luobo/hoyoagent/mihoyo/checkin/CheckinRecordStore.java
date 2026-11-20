package com.luobo.hoyoagent.mihoyo.checkin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Stream;

/**
 * 签到记录的文件存储：<b>每人一个目录，一天一个 JSON</b>。
 *
 * <pre>
 * ~/.hoyo/checkin/
 * ├── 3f2a1b0c9d8e7f60/          ← sha256(userId) 前 16 位，不暴露 openid
 * │   ├── 2026-11-20.json
 * │   └── 2026-11-19.json
 * └── 2026-11-18.json            ← M3 之前的旧文件：按内容里的 userId 认领
 * </pre>
 *
 * <p>为什么按用户分目录而不是"一个文件装所有人"：一天一个文件的语义是"这天只跑过一次"，
 * 多用户下这个假设会直接把别人的结果覆盖掉。分目录还能让"解绑时清掉这个人"变成删一个目录。
 *
 * <p>两条硬约束不变：
 * <ul>
 *   <li><b>读坏了不能炸</b>——解析失败当"没有记录"，宁可多签一次（服务端幂等兜底）；</li>
 *   <li><b>写失败不能炸</b>——记录是观测，不是业务结果。</li>
 * </ul>
 */
@Component
public class CheckinRecordStore {

    private static final Logger log = LoggerFactory.getLogger(CheckinRecordStore.class);

    private static final String SUFFIX = ".json";

    private final Path root;

    private final ObjectMapper mapper;

    /** 单进程内串行化写；跨进程不做（定时任务只有一个） */
    private final Object writeLock = new Object();

    @Autowired
    public CheckinRecordStore(@Value("${hoyo.checkin.dir:}") String directory, ObjectMapper mapper) {
        this(directory == null || directory.isBlank()
                ? Path.of(System.getProperty("user.home"), ".hoyo", "checkin")
                : Path.of(directory), mapper);
    }


    CheckinRecordStore(Path root, ObjectMapper mapper) {
        this.root = root;
        this.mapper = mapper;
    }

    public Path root() {
        return root;
    }

    /**
     * 先找该用户目录，再回退到根目录的旧文件（只认内容里 userId 对得上的）。
     * 旧文件不迁移：读得到就够了，下次 save 会写到新目录。
     */
    public Optional<CheckinRecord> find(String userId, String date) {
        Optional<CheckinRecord> own = read(userDir(userId).resolve(date + SUFFIX));
        if (own.isPresent()) {
            return own;
        }
        return read(root.resolve(date + SUFFIX)).filter(r -> userId.equals(r.userId()));
    }

    /** 该用户最近的 {@code limit} 条，按日期倒序；同一天以用户目录里的为准。 */
    public List<CheckinRecord> recent(String userId, int limit) {
        Map<String, CheckinRecord> byDate = new LinkedHashMap<>();
        for (CheckinRecord record : readDir(userDir(userId))) {
            byDate.put(record.date(), record);
        }
        for (CheckinRecord record : readDir(root)) {     // 旧文件兜底
            if (userId.equals(record.userId())) {
                byDate.putIfAbsent(record.date(), record);
            }
        }
        return byDate.values().stream()
                .sorted(Comparator.comparing(CheckinRecord::date).reversed())
                .limit(limit)
                .toList();
    }

    /** 写到自己那个目录里；userId 从记录本身取，调用方不用传。 */
    public void save(CheckinRecord record) {
        synchronized (writeLock) {
            try {
                Path dir = userDir(record.userId());
                Files.createDirectories(dir);
                Path target = dir.resolve(record.date() + SUFFIX);
                Path temp = target.resolveSibling(target.getFileName() + ".tmp");
                Files.writeString(temp, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(record),
                        StandardCharsets.UTF_8);
                move(temp, target);
            } catch (IOException | RuntimeException e) {
                log.error("签到记录写入失败（不影响签到结果）: date={} err={}", record.date(), e.getMessage());
            }
        }
    }

    /** 解绑时调用：删掉这个用户的全部历史，返回删掉的文件数。 */
    public int deleteAll(String userId) {
        Path dir = userDir(userId);
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        int deleted = 0;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.toList()) {
                if (Files.deleteIfExists(file)) {
                    deleted++;
                }
            }
            Files.deleteIfExists(dir);
        } catch (IOException e) {
            log.warn("签到记录删除失败: user={} err={}", userId, e.getMessage());
        }
        return deleted;
    }

    private List<CheckinRecord> readDir(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            List<CheckinRecord> found = new ArrayList<>();
            files.filter(p -> p.getFileName().toString().endsWith(SUFFIX))
                    .forEach(p -> read(p).ifPresent(found::add));
            return found;
        } catch (IOException e) {
            log.warn("签到记录目录读取失败: {} err={}", dir, e.getMessage());
            return List.of();
        }
    }

    private Optional<CheckinRecord> read(Path file) {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            return Optional.ofNullable(mapper.readValue(json, CheckinRecord.class));
        } catch (IOException | RuntimeException e) {
            log.warn("签到记录读取失败，当成没有记录: {} err={}", file.getFileName(), e.getMessage());
            return Optional.empty();
        }
    }

    private static void move(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 目录名 = sha256(userId) 前 16 位，和 FileAccountStore 一个套路。 */
    private static String fileNameOf(String userId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(userId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 调试用：把记录压成一行，日志和状态页共用同一套措辞。 */
    static String describe(CheckinRecord record) {
        return "%s %s（%s）：%s".formatted(record.date(), record.outcome(),
                record.trigger(), java.util.Objects.toString(record.message(), ""));
    }

    /** 某个用户的记录目录（包可见：测试要数文件）。 */
    Path userDir(String userId) {
        return root.resolve(fileNameOf(userId));
    }
}
