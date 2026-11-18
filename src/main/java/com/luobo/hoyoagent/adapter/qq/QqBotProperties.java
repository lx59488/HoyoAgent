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
}
