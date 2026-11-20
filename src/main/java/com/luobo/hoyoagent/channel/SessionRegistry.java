package com.luobo.hoyoagent.channel;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 会话 → 平台用户 的映射。
 *
 * <p>为什么不让代码去"解析 sessionId 前缀"：那样任何人构造一个
 * {@code qqbot:c2c:<别人的openid>} 当 memoryId 打 HTTP 接口，就能冒充别人。
 * 只有 QQ 适配器在收到真实事件时才注册；查不到的会话一律落到 {@code local}（网页调试页）。
 */
@Component
public class SessionRegistry {

    private static final int MAX_ENTRIES = 1000;

    private final String localUserId;

    /** 访问序 LRU：会话再多也不会无限涨。 */
    private final Map<String, String> sessions = Collections.synchronizedMap(
            new LinkedHashMap<String, String>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_ENTRIES;
                }
            });

    public SessionRegistry(@Value("${hoyo.user-id:local}") String localUserId) {
        this.localUserId = localUserId == null || localUserId.isBlank() ? "local" : localUserId.trim();
    }

    public void register(String memoryId, String userId) {
        if (memoryId == null || memoryId.isBlank() || userId == null || userId.isBlank()) {
            return;
        }
        sessions.put(memoryId, userId);
    }

    public Optional<String> userIdOf(String memoryId) {
        return memoryId == null ? Optional.empty() : Optional.ofNullable(sessions.get(memoryId));
    }

    /** 查不到就当 local：HTTP 调试页、一次性会话都走这里。 */
    public String userIdOrLocal(String memoryId) {
        return userIdOf(memoryId).orElse(localUserId);
    }

    public String localUserId() {
        return localUserId;
    }
}
