package com.luobo.hoyoagent.mihoyo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

/**
 * 项目级配置。
 *
 * <p>UA / app_version / client_type / salt 不再放这里：它们必须成组出现，
 * 现在由 {@code Profiles} 里的 {@code HeaderProfile} 统一管理（扫码系、换票系、签到、战绩各一套）。
 * 配置项留在这里只会有两种结局——要么没人读，要么和代码里的常量打架。
 */
@ConfigurationProperties(prefix = "hoyo")
public record HoyoProperties(
        Map<String, String> hosts,
        Duration timeout,
        String deviceId
) {
}
