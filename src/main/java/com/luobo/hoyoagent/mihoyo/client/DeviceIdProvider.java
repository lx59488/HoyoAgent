package com.luobo.hoyoagent.mihoyo.client;

import com.luobo.hoyoagent.mihoyo.config.HoyoProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Component
public class DeviceIdProvider {

    private static final Logger log = LoggerFactory.getLogger(DeviceIdProvider.class);

    private static final String FILE_NAME = "device-id";

    private final String deviceId;


    @Autowired
    public DeviceIdProvider(HoyoProperties props) {
        this(resolve(props));
    }

    private DeviceIdProvider(String deviceId) {
        this.deviceId = deviceId;
    }

    /** 测试专用 */
    static DeviceIdProvider forFile(Path file) {
        return new DeviceIdProvider(loadOrCreate(file));
    }
    public String get() {
        return deviceId;
    }

    private static String resolve(HoyoProperties props) {
        // 优先级 1：配置里写死的（调试/复现抓包时用）
        if (props.deviceId() != null && !props.deviceId().isBlank()) {
            log.info("device_id 使用配置值");
            return props.deviceId().trim();
        }
        // 优先级 2：主目录下的文件
        Path file = Path.of(System.getProperty("user.home"), ".hoyo", FILE_NAME);
        return loadOrCreate(file);
    }

    private static String loadOrCreate(Path file) {
        try {
            if (Files.exists(file)) {
                String existing = Files.readString(file, StandardCharsets.UTF_8).trim();
                if (isUuid(existing)) {
                    log.info("device_id 已加载: {}", file);
                    return existing;
                }
                log.warn("device-id 文件内容不是合法 UUID，将重新生成: {}", file);
            }

            String fresh = UUID.randomUUID().toString();
            writeAtomically(file, fresh);
            // 注意：不打印 deviceId 本身，它是设备指纹
            log.info("已生成新的 device_id 并写入 {}", file);
            return fresh;

        } catch (IOException e) {
            throw new IllegalStateException("device_id 读写失败: " + file, e);
        }
    }

    private static void writeAtomically(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // 某些文件系统不支持原子移动，降级
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean isUuid(String s) {
        try {
            UUID.fromString(s);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
