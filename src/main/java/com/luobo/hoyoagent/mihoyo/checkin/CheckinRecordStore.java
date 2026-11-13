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
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 签到记录的文件存储：一天一个 JSON，默认 {@code ~/.hoyo/checkin/}。
 *
 * <p>为什么先上文件而不是数据库：单用户、每天一条、只是给"今天跑过没有"和图省事查一眼用的。
 * 多用户时按 {@code userId} 分目录或直接换库，调用方（{@link CheckinScheduler}）不用改。
 *
 * <p>两条硬约束：
 * <ul>
 *   <li><b>读坏了不能炸</b>——解析失败当"没有记录"，宁可多签一次（服务端幂等兜底），
 *       也不能让一个坏文件把整天的定时任务卡死。</li>
 *   <li><b>写失败不能炸</b>——记录是观测，不是业务结果；丢了记录也不能让已经签上的号报错。</li>
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

    public Optional<CheckinRecord> find(String date) {
        return read(fileOf(date));
    }

    /** 最近的 {@code limit} 条，按日期倒序；中间缺的日子直接跳过。 */
    public List<CheckinRecord> recent(int limit) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(root)) {
            return files.filter(p -> p.getFileName().toString().endsWith(SUFFIX))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .limit(limit)
                    .map(this::read)
                    .flatMap(Optional::stream)
                    .toList();
        } catch (IOException e) {
            log.warn("签到记录目录读取失败: {} err={}", root, e.getMessage());
            return List.of();
        }
    }

    public void save(CheckinRecord record) {
        synchronized (writeLock) {
            try {
                Files.createDirectories(root);
                Path target = fileOf(record.date());
                Path temp = target.resolveSibling(target.getFileName() + ".tmp");
                Files.writeString(temp, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(record),
                        StandardCharsets.UTF_8);
                move(temp, target);
            } catch (IOException | RuntimeException e) {
                log.error("签到记录写入失败（不影响签到结果）: date={} err={}", record.date(), e.getMessage());
            }
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

    private Path fileOf(String date) {
        return root.resolve(date + SUFFIX);
    }

    private static void move(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 调试用：把记录压成一行，日志和状态页共用同一套措辞。 */
    static String describe(CheckinRecord record) {
        return "%s %s（%s）：%s".formatted(record.date(), record.outcome(),
                record.trigger(), Objects.toString(record.message(), ""));
    }
}
