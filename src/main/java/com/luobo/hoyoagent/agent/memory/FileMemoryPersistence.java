package com.luobo.hoyoagent.agent.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

@Component
public class FileMemoryPersistence implements MemoryPersistence {

    private static final Logger log = LoggerFactory.getLogger(FileMemoryPersistence.class);

    /** 磁盘格式版本。不认识的版本一律当"没有记忆"——和 CheckinRecordStore 的约定一致。 */
    private static final int FORMAT_VERSION = 1;

    private final Path root;
    private final Duration retention;
    private final ObjectMapper mapper;

    public FileMemoryPersistence(@Value("${hoyo.memory.dir:}") String dir,
                                 @Value("${hoyo.memory.retention:30d}") Duration retention,
                                 ObjectMapper mapper) {
        this.root = dir == null || dir.isBlank()
                ? Path.of(System.getProperty("user.home"), ".hoyo", "memory")
                : Path.of(dir);
        this.retention = retention;
        this.mapper = mapper;
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("创建记忆目录失败: " + root, e);
        }
        log.info("[MEMORY] 落盘目录={} 保留期={}", root, retention);
    }

    @Override
    public Optional<Stored> load(String sessionId) {
        Path file = fileOf(sessionId);
        if (!Files.isReadable(file)) {
            return Optional.empty();
        }
        try {
            JsonNode node = mapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
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
        Path file = fileOf(sessionId);
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("v", FORMAT_VERSION);
            envelope.put("updatedAt", updatedAt.toString());
            envelope.put("messages", mapper.readTree(ChatMessageSerializer.messagesToJson(messages)));

            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, mapper.writeValueAsString(envelope), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
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
        try (Stream<Path> files = Files.list(root)) {
            files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .forEach(path -> {
                        try {
                            if (Files.getLastModifiedTime(path).toInstant().isBefore(cutoff)) {
                                Files.deleteIfExists(path);
                                log.info("[MEMORY] 清理过期记忆 {}", path.getFileName());
                            }
                        } catch (IOException e) {
                            log.warn("[MEMORY] 清理失败: {} ({})", path.getFileName(), e.toString());
                        }
                    });
        } catch (IOException e) {
            log.warn("[MEMORY] 扫描记忆目录失败: {}", e.toString());
        }
    }

    /** sessionId 里有冒号和 openid，不能直接当文件名；用 sha256 前 32 位，和 FileAccountStore 一个套路。 */
    Path fileOf(String sessionId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sessionId.getBytes(StandardCharsets.UTF_8));
            return root.resolve(HexFormat.of().formatHex(digest).substring(0, 32) + ".json");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
