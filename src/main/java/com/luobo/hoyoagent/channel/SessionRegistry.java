package com.luobo.hoyoagent.channel;

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
 * 只有平台适配器在收到真实事件时才注册。
 *
 * <p>查不到就是<b>没有身份</b>：调用方必须显式处理（工具层回"请先绑定"），
 * 而不是回退到某个默认账号——那等于把 owner 的凭据借给任何匿名请求。
 */
@Component
public class SessionRegistry {

    private static final int MAX_ENTRIES = 1000;


    /** 访问序 LRU：会话再多也不会无限涨。 */
    private final Map<String, String> sessions = Collections.synchronizedMap(
            new LinkedHashMap<String, String>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_ENTRIES;
                }
            });


    public void register(String memoryId, String userId) {
        if (memoryId == null || memoryId.isBlank() || userId == null || userId.isBlank()) {
            return;
        }
        sessions.put(memoryId, userId);
    }

    public Optional<String> userIdOf(String memoryId) {
        return memoryId == null ? Optional.empty() : Optional.ofNullable(sessions.get(memoryId));
    }
}
