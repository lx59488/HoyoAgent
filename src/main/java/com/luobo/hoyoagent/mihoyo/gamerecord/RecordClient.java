package com.luobo.hoyoagent.mihoyo.gamerecord;

import com.luobo.hoyoagent.common.exception.BusinessException;
import com.luobo.hoyoagent.common.exception.ErrorCode;
import com.luobo.hoyoagent.mihoyo.MihoyoEndpoints;
import com.luobo.hoyoagent.mihoyo.credential.Credential;
import com.luobo.hoyoagent.mihoyo.client.DeviceIdProvider;
import com.luobo.hoyoagent.mihoyo.role.GameRole;
import com.luobo.hoyoagent.mihoyo.transport.MihoyoHost;
import com.luobo.hoyoagent.mihoyo.transport.MihoyoHttp;
import com.luobo.hoyoagent.mihoyo.transport.MihoyoRequest;
import com.luobo.hoyoagent.mihoyo.transport.MihoyoResult;
import com.luobo.hoyoagent.mihoyo.transport.Profiles;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * 战绩/账号类接口：绑定角色、App 配置。
 *
 * <p>2026-11-13：{@code character/list} 已删除——FufuLauncher 根本没有这个接口的实现，
 * 且新账号上恒返回 {@code 5003}（旧账号同请求正常，已确认是账号侧），继续维护没有收益。
 * 依赖它的 {@code PlayerTool} / {@code PlayerService} / {@code CharacterDto} 一并移除。
 *
 * <p>身份来自 {@link AccountService}（cookie）与 {@link DeviceIdProvider}（device_id），
 * 头部由 {@link Profiles#RECORD} 统一给出——这里不再自己 new OkHttp、也不自己拼 header。
 */
@Component
public class RecordClient {

    private final MihoyoHttp http;

    private final MihoyoEndpoints endpoints;

    private final DeviceIdProvider deviceId;

    public RecordClient(MihoyoHttp http,
                        MihoyoEndpoints endpoints,
                        DeviceIdProvider deviceId) {
        this.http = http;
        this.endpoints = endpoints;
        this.deviceId = deviceId;
    }

    /** 该 cookie 绑定的游戏角色（不需要 DS，但需要 cookie）。 */
    public List<GameRole> roles(Credential credential) {
        MihoyoResult result = http.send(MihoyoRequest.get(
                endpoints.baseUrl(MihoyoHost.TAKUMI) + "/binding/api/getUserGameRolesByCookie",
                Map.of(), headers(credential)));
        ensureOk(result, "读取绑定角色");

        return result.data().path("list").valueStream()
                .map(n -> new GameRole(
                        n.path("game_biz").asString(""),
                        n.path("region").asString(""),
                        n.path("game_uid").asString(""),
                        n.path("nickname").asString(""),
                        n.path("level").asInt(0)))
                .toList();
    }

    /** 公开接口：不需要 cookie，也不需要 DS。 */
    public JsonNode getAppConfig(int gids) {
        MihoyoResult result = http.send(MihoyoRequest.get(
                endpoints.baseUrl(MihoyoHost.BBS) + "/apihub/wapi/getAppConfig",
                Map.of("gids", String.valueOf(gids)),
                Profiles.RECORD.headers(null, deviceId.get(), null, null, "")));
        ensureOk(result, "读取 App 配置");
        return result.data();
    }

    private Map<String, String> headers(Credential credential) {
        if (credential == null || credential.cookieHeader() == null || credential.cookieHeader().isBlank()) {
            throw new BusinessException(ErrorCode.MOYO_AUTH_EXPIRED);
        }
        return Profiles.RECORD.headers(credential.cookieHeader(), deviceId.get(), null, null, "");
    }

    private static void ensureOk(MihoyoResult result, String action) {
        if (!result.ok()) {
            throw new BusinessException(result.error().errorCode(),
                    action + "失败：" + result.error().userMessage());
        }
    }
}
