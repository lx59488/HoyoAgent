package com.luobo.hoyoagent.mihoyo.role;

import com.luobo.hoyoagent.mihoyo.credential.Credential;
import com.luobo.hoyoagent.mihoyo.gamerecord.RecordClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 当前账号绑定的角色列表，带 10 分钟缓存。
 *
 * <p>为什么要这一层：{@code HOYO_ROLE_ID} 是"单账号时代"的临时办法，换账号就会指向别人的号。
 * 实测里这个坑很具体——扫了新号以后继续用旧 roleId 打签到，返回的是 {@code -100 请登录后重试}，
 * 看起来像"没登录"，其实是"这个 uid 不属于当前账号"。
 */
@Service
public class GameRoleService {

    private static final Logger log = LoggerFactory.getLogger(GameRoleService.class);

    private static final Duration TTL = Duration.ofMinutes(10);

    private final RecordClient recordClient;

    /** key = 米游社 uid：换绑后 uid 变了，天然是一份新缓存，不会读到上一个号的角色。 */
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public GameRoleService(RecordClient recordClient) {
        this.recordClient = recordClient;
    }

    public List<GameRole> roles(Credential credential) {
        String key = credential.uid();
        if (key == null || key.isBlank()) {
            return recordClient.roles(credential);      // 没 uid 就没法当 key，直连
        }
        Cached cached = cache.get(key);
        if (cached != null && Instant.now().isBefore(cached.loadedAt().plus(TTL))) {
            return cached.roles();
        }
        List<GameRole> fresh = List.copyOf(recordClient.roles(credential));
        cache.put(key, new Cached(fresh, Instant.now()));
        log.info("已加载绑定角色 {} 个（uid={}）: {}", fresh.size(), key,
                fresh.stream().map(r -> r.gameBiz() + "/" + r.gameUid()).toList());
        return fresh;
    }

    /** 某个游戏下的第一个角色（原神/星铁一般只有一条）。 */
    public Optional<GameRole> firstOf(Credential credential, String gameBiz) {
        return roles(credential).stream().filter(r -> r.gameBiz().equals(gameBiz)).findFirst();
    }


    private record Cached(List<GameRole> roles, Instant loadedAt) {
    }
}
