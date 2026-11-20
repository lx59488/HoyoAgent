package com.luobo.hoyoagent.mihoyo.checkin;

import com.luobo.hoyoagent.common.exception.BusinessException;
import com.luobo.hoyoagent.common.exception.ErrorCode;
import com.luobo.hoyoagent.mihoyo.MihoyoEndpoints;
import com.luobo.hoyoagent.mihoyo.credential.Credential;
import com.luobo.hoyoagent.mihoyo.sign.DsSigner;
import com.luobo.hoyoagent.mihoyo.transport.MihoyoHttp;
import com.luobo.hoyoagent.mihoyo.transport.MihoyoRequest;
import com.luobo.hoyoagent.mihoyo.transport.MihoyoResult;
import com.luobo.hoyoagent.mihoyo.transport.Profiles;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * luna 签到系列接口（info / sign / home）。
 *
 * <p>base url 统一从 {@link MihoyoEndpoints} 取，和战绩、passport 走同一个覆盖入口；
 * 各游戏打哪个 host 由 {@link CheckinGame#host()} 决定。
 */
@Component
public class LunaClient {

    private final MihoyoHttp http;

    private final MihoyoEndpoints endpoints;

    private final ObjectMapper mapper;

    public LunaClient(MihoyoHttp http, MihoyoEndpoints endpoints, ObjectMapper mapper) {
        this.http = http;
        this.endpoints = endpoints;
        this.mapper = mapper;
    }

    public MihoyoResult info(Credential credential, CheckinGame game, String region, String uid) {
        return call(credential, game, "/info", Map.of(
                "lang", "zh-cn",
                "act_id", game.actId(),
                "region", region,
                "uid", uid), null, game.signGame());
    }

    public MihoyoResult sign(Credential credential, CheckinGame game, String region, String uid) {
        String body = mapper.writeValueAsString(Map.of(
                "act_id", game.actId(), "region", region, "uid", uid));
        return call(credential, game, "/sign", Map.of(), body, game.signGame());
    }

    public MihoyoResult home(Credential credential, CheckinGame game) {
        return call(credential, game, "/home", Map.of(
                "lang", "zh-cn",
                "act_id", game.actId()
        ), null, game.signGame());
    }

    private MihoyoResult call(Credential credential, CheckinGame game, String action,
                              Map<String, String> query, String body, String signGame) {
        if (credential == null || !credential.hasCookie()) {
            throw new BusinessException(ErrorCode.MOYO_AUTH_EXPIRED);
        }
        String cookie = credential.cookieHeader();
        Map<String, String> headers = Profiles.CHECKIN.headers(cookie, DsSigner.md5(cookie), null, body, "");
        if (signGame != null) {
            headers.put("x-rpc-signgame", signGame);
        }
        String url = endpoints.baseUrl(game.host()) + game.path() + action;
        MihoyoRequest request = body == null
                ? MihoyoRequest.get(url, query, headers)
                : MihoyoRequest.post(url, body, headers);
        return http.send(request);
    }
}
