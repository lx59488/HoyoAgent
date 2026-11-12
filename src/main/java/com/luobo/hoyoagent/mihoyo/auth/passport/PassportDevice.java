package com.luobo.hoyoagent.mihoyo.auth.passport;

import com.luobo.hoyoagent.mihoyo.sign.DsSigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * passport 扫码用的设备身份（{@code x-rpc-device_id} + {@code x-rpc-device_fp}）。
 *
 * <p><b>为什么要持久化</b>：实测 {@code createQRLogin} 与 {@code queryQRLoginStatus} 必须用同一个
 * device_id，否则轮询直接返回 {@code -3503 当前设备或网络环境存在风险}。所以这对值生成一次就要固定下来。
 *
 * <p>生成规则照抄 FufuLauncher：16 位大写 hex；fp = md5(一段紧凑 JSON)，JSON 里的字段顺序也要一致。
 * 文件放在 {@code ~/.hoyo/passport-device}，不进仓库。
 */
@Component
public class PassportDevice {

    private static final Logger log = LoggerFactory.getLogger(PassportDevice.class);

    private static final Path DEFAULT_FILE = Path.of(System.getProperty("user.home"), ".hoyo", "passport-device");

    private static final SecureRandom RANDOM = new SecureRandom();

    private final String deviceId;

    private final String deviceFp;

    public PassportDevice() {
        this(DEFAULT_FILE);
    }

    PassportDevice(Path file) {
        Identity identity = loadOrCreate(file);
        this.deviceId = identity.deviceId();
        this.deviceFp = identity.deviceFp();
    }

    public String deviceId() {
        return deviceId;
    }

    public String deviceFingerprint() {
        return deviceFp;
    }

    private record Identity(String deviceId, String deviceFp) {
    }

    private static Identity loadOrCreate(Path file) {
        try {
            if (Files.exists(file)) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                String id = valueOf(lines, "device_id");
                String fp = valueOf(lines, "device_fp");
                if (id != null && id.matches("[0-9A-F]{16}") && fp != null && fp.matches("[0-9a-f]{32}")) {
                    log.info("passport 设备身份已加载: {}", file);
                    return new Identity(id, fp);
                }
                log.warn("passport-device 文件内容不合法，将重新生成: {}", file);
            }
            Identity fresh = generate();
            write(file, fresh);
            log.info("已生成新的 passport 设备身份并写入 {}", file);
            return fresh;
        } catch (IOException e) {
            // 登录本身不依赖这个文件；读写失败就退化成"本次进程内固定"，不要因此起不来
            log.warn("passport-device 读写失败，使用进程内临时身份: {}", e.getMessage());
            return generate();
        }
    }

    private static Identity generate() {
        String deviceId = HexFormat.of().withUpperCase().formatHex(randomBytes(8));
        String seedId = HexFormat.of().formatHex(randomBytes(8));
        long seedTime = Instant.now().getEpochSecond();
        // 字段顺序必须与 FufuLauncher 的匿名对象一致，否则 md5 对不上
        String json = "{\"device_id\":\"" + deviceId + "\",\"seed_id\":\"" + seedId
                + "\",\"seed_time\":" + seedTime
                + ",\"platform\":\"2\",\"device_fp\":\"\",\"app_name\":\"bbs_cn\"}";
        return new Identity(deviceId, DsSigner.md5(json));
    }

    private static byte[] randomBytes(int len) {
        byte[] bytes = new byte[len];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static String valueOf(List<String> lines, String key) {
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith(key + "=")) {
                return trimmed.substring(key.length() + 1).trim();
            }
        }
        return null;
    }

    private static void write(Path file, Identity identity) throws IOException {
        Files.createDirectories(file.getParent());
        String content = "device_id=" + identity.deviceId()
                + "\ndevice_fp=" + identity.deviceFp()
                + "\ncreated_at=" + Instant.now()
                + "\n";
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
