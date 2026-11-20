package com.luobo.hoyoagent.adapter.qq;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "qq")
public record QqBotProperties(
        boolean enabled,
        String appId,
        String clientSecret,
        List<String> allowlist,      // 存的是 openid，不是 QQ 号
        String apiBase,
        int maxReplies,
        int replyChunkChars
        ) {

    public QqBotProperties {
        appId = appId == null ? "" : appId.trim();
        clientSecret = clientSecret == null ? "" : clientSecret.trim();
        allowlist = allowlist == null ? List.of()
                : allowlist.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).toList();
        apiBase = (apiBase == null || apiBase.isBlank()) ? "https://api.sgroup.qq.com" : apiBase.trim();
        maxReplies = (maxReplies <= 0 || maxReplies > 4) ? 4 : maxReplies;   // 平台硬上限就是 4
        replyChunkChars = replyChunkChars <= 0 ? 600 : replyChunkChars;
    }

    /**
     * 日志用的掩码：只留前 4 位和后 4 位，太短的整串隐掉。
     * 只有一处例外不掩码——白名单未命中时（见 {@code QqBotDispatcher}），
     * 那一行是首次配置唯一的值来源。
     */
    public static String mask(String id) {
        if (id == null || id.isBlank()) return "***";
        return id.length() <= 8 ? "***" : id.substring(0, 4) + "***" + id.substring(id.length() - 4);
    }

    /**
     * 白名单条目长得像 QQ 号（纯数字）。
     * 本通道的事件里只有 openid，填 QQ 号永远匹配不上，启动时要主动提醒。
     */
    public static boolean looksLikeQqNumber(String entry) {
        return entry != null
                && entry.length() >= 5 && entry.length() <= 12
                && entry.chars().allMatch(Character::isDigit);
    }
}
