package com.luobo.hoyoagent.mihoyo.api;

import com.luobo.hoyoagent.mihoyo.client.MihoyoHttpClient;
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
        return http.get("/apihub/wapi/getAppConfig",
                Map.of("gids", String.valueOf(gids)), false);
    }
}
