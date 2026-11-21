package com.luobo.hoyoagent.agent.memory;

import com.luobo.hoyoagent.common.storage.LocalFileStore;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Component
@ConditionalOnProperty(name = "hoyo.storage.backend", havingValue = "file", matchIfMissing = true)
public class FileMemoryStore implements MemoryStore {
    private static final Logger log = LoggerFactory.getLogger(FileMemoryStore.class);

    private static final String SUFFIX = ".json";

    /** 磁盘格式版本。不认识的版本一律当"没有记忆"——和 CheckinRecordStore 的约定一致。 */
    private static final int FORMAT_VERSION = 1;

    private final LocalFileStore store;

    private final Duration retention;

    private final ObjectMapper mapper;

    @Autowired
    public FileMemoryStore(@Value("${hoyo.memory.dir:}") String dir,
                           @Value("${hoyo.state-dir:}") String stateDir,
                           @Value("${hoyo.memory.retention:30d}") Duration retention,
                           ObjectMapper mapper) {
        this(LocalFileStore.resolve(dir, LocalFileStore.stateRoot(stateDir).resolve("memory")), retention, mapper);
    }

    /** 单测用：直接给目录。 */
    FileMemoryStore(String dir, Duration retention, ObjectMapper mapper) {
        this(LocalFileStore.at(Path.of(dir)), retention, mapper);
    }

    private FileMemoryStore(LocalFileStore store, Duration retention, ObjectMapper mapper) {
        this.store = store;
        this.retention = retention;
        this.mapper = mapper;
        // 记忆目录建不出来就没有"落盘记忆"，这是启动期事实，早炸比晚炸好
        store.ensureRoot();
        log.info("[MEMORY] 落盘目录={} 保留期={}", store.root(), retention);
    }


    @Override
    public Optional<Stored> load(String sessionId) {
        Path file = fileOf(sessionId);
        if (!Files.isReadable(file)) {
            return Optional.empty();
        }
        try {
            JsonNode node = mapper.readTree(LocalFileStore.readString(file));
            int version = node.path("v").asInt(-1);
            if (version != FORMAT_VERSION) {
                log.warn("[MEMORY] 未知格式 v={}，当作没有记忆: {}", version, file.getFileName());
                return Optional.empty();
            }
            Instant updatedAt = Instant.parse(node.path("updatedAt").asString("1970-01-01T00:00:00Z"));
            List<ChatMessage> messages = ChatMessageDeserializer.messagesFromJson(node.path("messages").toString());
            return Optional.of(new Stored(messages, updatedAt));
        } catch (IOException | RuntimeException e) {
            // 读坏当没有：宁可少记一轮，也不要一启动就炸
            log.warn("[MEMORY] 读记忆文件失败，当作没有: {} ({})", file.getFileName(), e.toString());
            return Optional.empty();
        }
    }

    @Override
    public void save(String sessionId, List<ChatMessage> messages, Instant updatedAt) {
        // 先算路径：fileOf 对空 sessionId 会抛，放在 try 里会让 catch 再抛一次
        Path file = fileOf(sessionId);
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("v", FORMAT_VERSION);
            envelope.put("updatedAt", updatedAt.toString());
            envelope.put("messages", mapper.readTree(ChatMessageSerializer.messagesToJson(messages)));

            LocalFileStore.writeAtomic(file, mapper.writeValueAsString(envelope));
        } catch (IOException | RuntimeException e) {
            // 写失败只记日志：记忆是体验，不是业务事实
            log.warn("[MEMORY] 写记忆文件失败: {} ({})", file.getFileName(), e.toString());
        }
    }

    @Override
    public void delete(String sessionId) {
        try {
            Files.deleteIfExists(fileOf(sessionId));
        } catch (IOException e) {
            log.warn("[MEMORY] 删记忆文件失败: {}", e.toString());
        }
    }

    /** 每天 04:30 清一次超过保留期的文件。按修改时间算，不解析内容。 */
    @Scheduled(cron = "0 30 4 * * *", zone = "Asia/Shanghai")
    public void purgeExpired() {
        Instant cutoff = Instant.now().minus(retention);
        try {
            for (Path path : LocalFileStore.listFiles(store.root(), SUFFIX)) {
                try {
                    if (Files.getLastModifiedTime(path).toInstant().isBefore(cutoff)) {
                        Files.deleteIfExists(path);
                        log.info("[MEMORY] 清理过期记忆 {}", path.getFileName());
                    }
                } catch (IOException e) {
                    log.warn("[MEMORY] 清理失败: {} ({})", path.getFileName(), e.toString());
                }
            }
        } catch (IOException e) {
            log.warn("[MEMORY] 扫描记忆目录失败: {}", e.toString());
        }
    }

    /** sessionId 里有冒号和 openid，不能直接当文件名；用 sha256 前 32 位，和 FileAccountStore 一个套路。 */
    Path fileOf(String sessionId) {
        return store.fileOf(sessionId, 32, SUFFIX);
    }
}
