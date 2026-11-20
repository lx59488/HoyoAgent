package com.luobo.hoyoagent.mihoyo.checkin;

import com.luobo.hoyoagent.common.exception.BusinessException;
import com.luobo.hoyoagent.common.exception.ErrorCode;
import com.luobo.hoyoagent.mihoyo.credential.Credential;
import com.luobo.hoyoagent.mihoyo.credential.CredentialProvider;
import com.luobo.hoyoagent.mihoyo.sign.DsSigner;
import com.luobo.hoyoagent.mihoyo.transport.MihoyoHttp;
import com.luobo.hoyoagent.mihoyo.transport.MihoyoRequest;
import com.luobo.hoyoagent.mihoyo.transport.MihoyoResult;
import com.luobo.hoyoagent.mihoyo.transport.Profiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

@Component
public class LunaClient {

    private static final Logger log = LoggerFactory.getLogger(LunaClient.class);

    private final MihoyoHttp http;

    private final ObjectMapper mapper;


    public LunaClient(MihoyoHttp http, ObjectMapper mapper) {
        this.mapper = mapper;
        this.http = http;
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
        if (credential == null || credential.cookieHeader() == null || credential.cookieHeader().isBlank()) {
            throw new BusinessException(ErrorCode.MOYO_AUTH_EXPIRED);
        }
        String cookie = credential.cookieHeader();
        Map<String, String> headers = Profiles.CHECKIN.headers(cookie, DsSigner.md5(cookie), null, body, "");
        if (signGame != null) {
            headers.put("x-rpc-signgame", signGame);
        }
        MihoyoRequest request = body == null
                ? MihoyoRequest.get(game.url(action), query, headers)
                : MihoyoRequest.post(game.url(action), body, headers);
        return http.send(request);
    }
}
