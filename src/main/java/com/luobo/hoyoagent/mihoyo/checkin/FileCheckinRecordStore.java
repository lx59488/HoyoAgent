package com.luobo.hoyoagent.mihoyo.checkin;

import com.luobo.hoyoagent.common.storage.LocalFileStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 签到记录的文件实现：<b>每人一个目录，一天一个 JSON</b>。
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
 * <p>旧目录布局（根目录下的 {@code YYYY-MM-DD.json}）仍然读得到，但不迁移：
 * 读得到就够了，下次 {@link #save} 会写到新目录。这段兼容逻辑只属于文件实现，
 * 不在 {@link CheckinRecordStore} 的契约里——换数据库时它会一起消失。
 */
@Component
public class FileCheckinRecordStore implements CheckinRecordStore{
    private static final Logger log = LoggerFactory.getLogger(FileCheckinRecordStore.class);

    private static final String SUFFIX = ".json";

    /** 目录名的 hex 位数：16 位 = 64 bit，够区分 userId，也不会把目录名撑长。 */
    private static final int DIR_HEX = 16;

    private final LocalFileStore store;

    private final ObjectMapper mapper;

    /** 单进程内串行化写；跨进程不做（定时任务只有一个） */
    private final Object writeLock = new Object();

    @Autowired
    public FileCheckinRecordStore(@Value("${hoyo.checkin.dir:}") String directory,
                                  @Value("${hoyo.state-dir:}") String stateDir,
                                  ObjectMapper mapper) {
        this(LocalFileStore.resolve(directory, LocalFileStore.stateRoot(stateDir).resolve("checkin")), mapper);
    }

    FileCheckinRecordStore(Path root, ObjectMapper mapper) {
        this(LocalFileStore.at(root), mapper);
    }

    private FileCheckinRecordStore(LocalFileStore store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    @Override
    public Optional<CheckinRecord> find(String userId, String date) {
        Optional<CheckinRecord> own = read(userDir(userId).resolve(date + SUFFIX));
        if (own.isPresent()) {
            return own;
        }
        return read(store.file(date + SUFFIX)).filter(r -> userId.equals(r.userId()));
    }

    @Override
    public List<CheckinRecord> recent(String userId, int limit) {
        Map<String, CheckinRecord> byDate = new LinkedHashMap<>();
        for (CheckinRecord record : readDir(userDir(userId))) {
            byDate.put(record.date(), record);
        }
        for (CheckinRecord record : readDir(store.root())) {     // 旧文件兜底
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
    @Override
    public void save(CheckinRecord record) {
        synchronized (writeLock) {
            try {
                Path target = userDir(record.userId()).resolve(record.date() + SUFFIX);
                Files.createDirectories(target.getParent());
                LocalFileStore.writeAtomic(target,
                        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(record));
            } catch (IOException | RuntimeException e) {
                log.error("签到记录写入失败（不影响签到结果）: date={} err={}", record.date(), e.getMessage());
            }
        }
    }

    @Override
    public int deleteAll(String userId) {
        Path dir = userDir(userId);
        int deleted = 0;
        try {
            // 后缀传空串 = 全部文件：临时文件、未来加的附属文件都一起清掉
            for (Path file : LocalFileStore.listFiles(dir, "")) {
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

    @Override
    public String location() {
        return store.root().toString();
    }

    private List<CheckinRecord> readDir(Path dir) {
        try {
            List<CheckinRecord> found = new ArrayList<>();
            LocalFileStore.listFiles(dir, SUFFIX).forEach(p -> read(p).ifPresent(found::add));
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
            return Optional.ofNullable(mapper.readValue(LocalFileStore.readString(file), CheckinRecord.class));
        } catch (IOException | RuntimeException e) {
            log.warn("签到记录读取失败，当成没有记录: {} err={}", file.getFileName(), e.getMessage());
            return Optional.empty();
        }
    }

    /** 某个用户的记录目录（包可见：测试要数文件）。 */
    Path userDir(String userId) {
        return store.dirOf(userId, DIR_HEX);
    }
}
