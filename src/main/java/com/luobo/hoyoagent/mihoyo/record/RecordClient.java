package com.luobo.hoyoagent.mihoyo.record;

import com.luobo.hoyoagent.common.exception.BusinessException;
import com.luobo.hoyoagent.common.exception.ErrorCode;
import com.luobo.hoyoagent.mihoyo.MihoyoEndpoints;
import com.luobo.hoyoagent.mihoyo.credential.CredentialProvider;
import com.luobo.hoyoagent.mihoyo.client.DeviceIdProvider;
import com.luobo.hoyoagent.mihoyo.dto.GameRole;
import com.luobo.hoyoagent.mihoyo.enums.MiyousheHost;
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
 * <p>身份来自 {@link CredentialProvider}（cookie）与 {@link DeviceIdProvider}（device_id），
 * 头部由 {@link Profiles#RECORD} 统一给出——这里不再自己 new OkHttp、也不自己拼 header。
 */
@Component
public class RecordClient {

    private final MihoyoHttp http;

    private final MihoyoEndpoints endpoints;

    private final CredentialProvider credentials;

    private final DeviceIdProvider deviceId;

    public RecordClient(MihoyoHttp http,
                        MihoyoEndpoints endpoints,
                        CredentialProvider credentials,
                        DeviceIdProvider deviceId) {
        this.http = http;
        this.endpoints = endpoints;
        this.credentials = credentials;
        this.deviceId = deviceId;
    }

    /** 当前 cookie 绑定的游戏角色（不需要 DS，但需要 cookie）。 */
    public List<GameRole> roles() {
        MihoyoResult result = http.send(MihoyoRequest.get(
                endpoints.baseUrl(MiyousheHost.TAKUMI) + "/binding/api/getUserGameRolesByCookie",
                Map.of(), headers(true)));
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
                endpoints.baseUrl(MiyousheHost.BBS) + "/apihub/wapi/getAppConfig",
                Map.of("gids", String.valueOf(gids)), headers(false)));
        ensureOk(result, "读取 App 配置");
        return result.data();
    }

    private Map<String, String> headers(boolean auth) {
        if (auth && !credentials.isPresent()) {
            throw new BusinessException(ErrorCode.MOYO_AUTH_EXPIRED);
        }
        String cookie = auth ? credentials.get().cookieHeader() : null;
        return Profiles.RECORD.headers(cookie, deviceId.get(), null, null, "");
    }

    private static void ensureOk(MihoyoResult result, String action) {
        if (!result.ok()) {
            throw new BusinessException(result.error().errorCode(),
                    action + "失败：" + result.error().userMessage());
        }
    }
}
