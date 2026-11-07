package com.luobo.hoyoagent.mihoyo.api;

import com.luobo.hoyoagent.mihoyo.client.MihoyoHttpClient;
import com.luobo.hoyoagent.mihoyo.enums.MiyousheHost;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Map;

@Component
public class MiyousheApi {

    private final MihoyoHttpClient http;

    public MiyousheApi(MihoyoHttpClient http) {
        this.http = http;
    }

    public JsonNode getAppConfig(int gids) {
        return http.get(MiyousheHost.BBS, "/apihub/wapi/getAppConfig",
                Map.of("gids", String.valueOf(gids)), false);
    }

    public JsonNode getCharacters(String roleId, String server) {
        return http.post(MiyousheHost.RECORD,
                "/game_record/app/genshin/api/character/list",
                Map.of("server", server, "role_id", roleId, "sort_type", 1),
                true);
    }
}
