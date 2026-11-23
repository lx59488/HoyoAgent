package com.luobo.hoyoagent.adapter.qq;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "qq")
public record QqBotProperties(
        boolean enabled,
        String appId,
        String clientSecret,
        String apiBase,
        int maxReplies,
        int replyChunkChars
        ) {

    public QqBotProperties {
        appId = appId == null ? "" : appId.trim();
        clientSecret = clientSecret == null ? "" : clientSecret.trim();
        apiBase = (apiBase == null || apiBase.isBlank()) ? "https://api.sgroup.qq.com" : apiBase.trim();
        maxReplies = (maxReplies <= 0 || maxReplies > 4) ? 4 : maxReplies;   // 平台硬上限就是 4
        replyChunkChars = replyChunkChars <= 0 ? 600 : replyChunkChars;
    }
}
