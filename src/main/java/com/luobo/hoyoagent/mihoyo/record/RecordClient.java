package com.luobo.hoyoagent.mihoyo.record;

import com.luobo.hoyoagent.common.exception.BusinessException;
import com.luobo.hoyoagent.mihoyo.MihoyoEndpoints;
import com.luobo.hoyoagent.mihoyo.dto.CharacterDto;
import com.luobo.hoyoagent.mihoyo.dto.GameRole;
import com.luobo.hoyoagent.mihoyo.enums.MiyousheHost;
import com.luobo.hoyoagent.mihoyo.transport.MihoyoHttp;
import com.luobo.hoyoagent.mihoyo.transport.MihoyoRequest;
import com.luobo.hoyoagent.mihoyo.transport.MihoyoResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * 战绩类接口（角色 / 战绩卡）。
 *
 * <p><b>TODO(Step 6)</b>：还没接上身份——需要注入 {@code CredentialProvider} 与
 * {@code DeviceIdProvider}，用 {@code Profiles.RECORD.headers(...)} 组装 cookie/device_id。
 * 在那之前本类发出的请求不带 cookie，只能返回 -100（当前未被任何调用方使用）。
 *
 * <p>即便还没接身份，这里也必须检查 retcode：静默返回空列表比报错更难查。
 */
@Component
public class RecordClient {

    private final MihoyoHttp http;
    private final ObjectMapper mapper;
    private final MihoyoEndpoints endpoints;

    public RecordClient(MihoyoHttp http, ObjectMapper mapper, MihoyoEndpoints endpoints) {
        this.http = http;
        this.mapper = mapper;
        this.endpoints = endpoints;
    }

    public List<GameRole> roles() {
        MihoyoResult result = http.send(MihoyoRequest.get(
                endpoints.baseUrl(MiyousheHost.TAKUMI) + "/binding/api/getUserGameRolesByCookie",
                Map.of(), Map.of()));
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

    public List<CharacterDto> characters(String roleId, String server) {
        String body = mapper.writeValueAsString(Map.of(
                "server", server, "role_id", roleId, "sort_type", 1));
        MihoyoResult result = http.send(MihoyoRequest.post(
                endpoints.baseUrl(MiyousheHost.RECORD) + "/game_record/app/genshin/api/character/list",
                body, Map.of()));
        ensureOk(result, "查询角色列表");

        CharacterListData parsed = mapper.treeToValue(result.data(), CharacterListData.class);
        return parsed.list();
    }

    public JsonNode getAppConfig(int gids) {
        MihoyoResult result = http.send(MihoyoRequest.get(
                endpoints.baseUrl(MiyousheHost.BBS) + "/apihub/wapi/getAppConfig",
                Map.of("gids", String.valueOf(gids)), Map.of()));
        ensureOk(result, "读取 App 配置");
        return result.data();
    }

    private static void ensureOk(MihoyoResult result, String action) {
        if (!result.ok()) {
            throw new BusinessException(result.error().errorCode(),
                    action + "失败：" + result.error().userMessage());
        }
    }

    public record CharacterListData(List<CharacterDto> list) {}
}
