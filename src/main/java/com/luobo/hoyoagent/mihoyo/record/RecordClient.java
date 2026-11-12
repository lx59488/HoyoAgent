package com.luobo.hoyoagent.mihoyo.record;

import com.luobo.hoyoagent.common.exception.BusinessException;
import com.luobo.hoyoagent.common.exception.ErrorCode;
import com.luobo.hoyoagent.mihoyo.MihoyoEndpoints;
import com.luobo.hoyoagent.mihoyo.credential.CredentialProvider;
import com.luobo.hoyoagent.mihoyo.client.DeviceIdProvider;
import com.luobo.hoyoagent.mihoyo.dto.CharacterDto;
import com.luobo.hoyoagent.mihoyo.dto.GameRole;
import com.luobo.hoyoagent.mihoyo.enums.MiyousheHost;
import com.luobo.hoyoagent.mihoyo.transport.MihoyoHttp;
import com.luobo.hoyoagent.mihoyo.transport.MihoyoRequest;
import com.luobo.hoyoagent.mihoyo.transport.MihoyoResult;
import com.luobo.hoyoagent.mihoyo.transport.Profiles;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * 战绩类接口：绑定角色、角色列表、App 配置。
 *
 * <p>身份来自 {@link CredentialProvider}（cookie）与 {@link DeviceIdProvider}（device_id），
 * 头部由 {@link Profiles#RECORD} 统一给出——这里不再自己 new OkHttp、也不自己拼 header。
 *
 * <p>约定：client 只做「URL + 头 + DTO」，retcode 只做"失败就抛"，不做展示层翻译。
 */
@Component
public class RecordClient {

    private final MihoyoHttp http;

    private final ObjectMapper mapper;

    private final MihoyoEndpoints endpoints;

    private final CredentialProvider credentials;

    private final DeviceIdProvider deviceId;

    public RecordClient(MihoyoHttp http,
                        ObjectMapper mapper,
                        MihoyoEndpoints endpoints,
                        CredentialProvider credentials,
                        DeviceIdProvider deviceId) {
        this.http = http;
        this.mapper = mapper;
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

    /** 角色列表。实测只认 v2 凭据；只有 v1 会返回无 message 的 5003。 */
    public List<CharacterDto> characters(String roleId, String server) {
        String body = mapper.writeValueAsString(Map.of(
                "server", server, "role_id", roleId, "sort_type", 1));
        MihoyoResult result = http.send(MihoyoRequest.post(
                endpoints.baseUrl(MiyousheHost.RECORD) + "/game_record/app/genshin/api/character/list",
                body, headers(true)));
        ensureOk(result, "查询角色列表");

        return mapper.treeToValue(result.data(), CharacterListData.class).list();
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

    public record CharacterListData(List<CharacterDto> list) {}
}
