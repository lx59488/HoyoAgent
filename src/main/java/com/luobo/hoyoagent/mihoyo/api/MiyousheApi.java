package com.luobo.hoyoagent.mihoyo.api;

import com.luobo.hoyoagent.mihoyo.client.MihoyoHttpClient;
import com.luobo.hoyoagent.mihoyo.dto.CharacterDto;
import com.luobo.hoyoagent.mihoyo.dto.GameRole;
import com.luobo.hoyoagent.mihoyo.enums.MiyousheHost;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class MiyousheApi {

    private final MihoyoHttpClient http;

    private final ObjectMapper mapper;


    public MiyousheApi(MihoyoHttpClient http, ObjectMapper mapper) {
        this.http = http;
        this.mapper = mapper;
    }


    public JsonNode getAppConfig(int gids) {
        return http.get(MiyousheHost.BBS, "/apihub/wapi/getAppConfig",
                Map.of("gids", String.valueOf(gids)), false);
    }

    public List<GameRole> getGameRoles() {
        JsonNode data = http.get(MiyousheHost.TAKUMI, "/binding/api/getUserGameRolesByCookie", Map.of(), true);
        List<GameRole> roles = new ArrayList<>();
        for (JsonNode node : data.path("list")) {
            roles.add(new GameRole(
                    node.path("game_biz").asString(""),
                    node.path("region").asString(""),
                    node.path("game_uid").asString(""),
                    node.path("nickname").asString(""),
                    node.path("level").asInt(0)));
        }
        return roles;
    }

    public List<CharacterDto> getCharacters(String roleId, String server) {
        JsonNode result = http.post(MiyousheHost.RECORD,
                "/game_record/app/genshin/api/character/list",
                Map.of("server", server, "role_id", roleId, "sort_type", 1),
                true);
        CharacterListData parsed = mapper.treeToValue(result, CharacterListData.class);
        return parsed.list();
    }

    public record CharacterListData(List<CharacterDto> list) {}


}
