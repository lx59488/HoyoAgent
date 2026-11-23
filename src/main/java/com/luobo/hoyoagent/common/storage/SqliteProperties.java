package com.luobo.hoyoagent.common.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * SQLite 存储配置。
 *
 * @param path        数据库文件路径；空 = {@code <state-dir>/hoyo.db}
 * @param busyTimeout 等锁上限；并发写时避免立刻 SQLITE_BUSY
 */
@ConfigurationProperties("hoyo.storage.sqlite")
public record SqliteProperties(String path, Duration busyTimeout) {

    public SqliteProperties {
        path = path == null ? "" : path.trim();
        busyTimeout = busyTimeout == null || busyTimeout.isNegative() || busyTimeout.isZero()
                ? Duration.ofSeconds(5)
                : busyTimeout;
    }
}
