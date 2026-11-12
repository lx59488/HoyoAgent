package com.luobo.hoyoagent.mihoyo.role;

import com.luobo.hoyoagent.mihoyo.api.MiyousheApi;
import com.luobo.hoyoagent.mihoyo.dto.GameRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

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

    private final MiyousheApi api;

    private volatile List<GameRole> cached = List.of();

    private volatile Instant loadedAt = Instant.EPOCH;

    public GameRoleService(MiyousheApi api) {
        this.api = api;
    }

    public List<GameRole> roles() {
        if (Instant.now().isBefore(loadedAt.plus(TTL))) {
            return cached;
        }
        List<GameRole> fresh = api.getGameRoles();
        cached = List.copyOf(fresh);
        loadedAt = Instant.now();
        log.info("已加载绑定角色 {} 个: {}", cached.size(),
                cached.stream().map(r -> r.gameBiz() + "/" + r.gameUid()).toList());
        return cached;
    }

    /** 某个游戏下的第一个角色（原神/星铁一般只有一条）。 */
    public Optional<GameRole> firstOf(String gameBiz) {
        return roles().stream().filter(r -> r.gameBiz().equals(gameBiz)).findFirst();
    }

    public void invalidate() {
        loadedAt = Instant.EPOCH;
    }
}
