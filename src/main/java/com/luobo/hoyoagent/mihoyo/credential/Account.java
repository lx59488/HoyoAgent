package com.luobo.hoyoagent.mihoyo.credential;

import java.time.Instant;
import java.util.Objects;

/**
 * 一个平台用户绑定的一份米游社凭据。
 *
 * <p>单用户阶段 {@code userId} 固定为 {@code local}；多用户后由聊天平台适配器注入
 * （QQ 号 / openid / HTTP 会话 id）。工具层仍然不应该看到它——那一步走 {@code @ToolMemoryId}。
 */
public record Account(String userId, Credential credential, Instant updatedAt) {

    public Account {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(credential, "credential");
    }

    public String uid() {
        return credential.uid();
    }

    public Account withCredential(Credential fresh, Instant now) {
        return new Account(userId, fresh, now);
    }
}
