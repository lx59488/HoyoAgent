package com.luobo.hoyoagent.mihoyo.client;

import com.luobo.hoyoagent.common.exception.BusinessException;
import com.luobo.hoyoagent.common.exception.ErrorCode;
import com.luobo.hoyoagent.mihoyo.MihoyoEndpoints;
import com.luobo.hoyoagent.mihoyo.auth.CredentialProvider;
import com.luobo.hoyoagent.mihoyo.enums.MiyousheHost;
import com.luobo.hoyoagent.mihoyo.sign.Signer;
import com.luobo.hoyoagent.mihoyo.config.HoyoProperties;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;


@Component
public class MihoyoHttpClient {

    public static final Logger log = LoggerFactory.getLogger(MihoyoHttpClient.class.getName());

    private final OkHttpClient client;
    private final HoyoProperties props;
    private final ObjectMapper mapper;
    private final CredentialProvider credentialProvider;
    private final DeviceIdProvider  deviceIdProvider;
    private final MihoyoEndpoints endpoints;
    private final Signer signer;

    public MihoyoHttpClient(HoyoProperties props,
                            ObjectMapper mapper,
                            CredentialProvider credentialProvider,
                            Signer signer,
                            DeviceIdProvider deviceIdProvider,
                            MihoyoEndpoints endpoints) {
        this.props = props;
        this.mapper = mapper;
        this.credentialProvider = credentialProvider;
        this.deviceIdProvider = deviceIdProvider;
        this.endpoints = endpoints;
        this.signer = signer;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(props.timeout())
                .readTimeout(props.timeout())
                .build();
    }


    public JsonNode get(MiyousheHost host, String path, Map<String, String> query, boolean auth) {
        HttpUrl.Builder url = HttpUrl.get(endpoints.baseUrl(host) + path).newBuilder();
        query.forEach(url::addQueryParameter);
        return execute(baseRequest(url.build(), auth).get().build(), path);
    }

    public JsonNode post(MiyousheHost host, String path, Object body, boolean auth) {
        String json = mapper.writeValueAsString(body);
        Request request = baseRequest(HttpUrl.get(endpoints.baseUrl(host) + path), auth)
                .post(RequestBody.create(json, MediaType.get("application/json;charset=UTF-8")))
                .build();
        return execute(request, path);
    }

    private JsonNode execute(Request request, String path) {
        try (Response resp = client.newCall(request).execute()) {
            String body = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) {
                throw new BusinessException(ErrorCode.MOYO_API_ERROR,
                        "米游社 HTTP 状态异常: " + resp.code());
            }
            JsonNode root = mapper.readTree(body);
            int retcode = root.path("retcode").asInt(-1);
            if (retcode != 0) {
                // retcode 是整数，可以进日志；message 是自由文本，不进日志
                log.warn("米游社接口返回非 0: path={} retcode={}", path, retcode);
                throw new BusinessException(mapRetcode(retcode), "米游社接口返回异常 retcode=" + retcode);
            }
            return root.path("data");
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.MOYO_NETWORK_ERROR, "米游社请求失败", e);
        }
    }

    private Request.Builder baseRequest(HttpUrl url, boolean auth) {
        Request.Builder req = new Request.Builder()
                .url(url)
                .header("User-Agent", props.userAgent())
                .header("Accept", "application/json")
                .header("Referer", props.referer())
                .header("x-rpc-app_version", props.appVersion())
                .header("x-rpc-client_type", props.clientType())
                .header("x-rpc-device_id", deviceIdProvider.get());

        if (auth) {
            if (!credentialProvider.isPresent()) {
                throw new BusinessException(ErrorCode.MOYO_AUTH_EXPIRED);
            }
            req.header("Cookie", credentialProvider.get().cookieHeader());
            signer.dsHeader().ifPresent(ds -> req.header("DS", ds));
        }
        return req;
    }


    private static ErrorCode mapRetcode(int retcode) {
        return switch (retcode) {
            case -100, 10001 -> ErrorCode.MOYO_AUTH_EXPIRED;
            case -5003, 10101 -> ErrorCode.MOYO_RATE_LIMITED;
            case 1034 -> ErrorCode.MOYO_NEED_VERIFY;
            default -> ErrorCode.MOYO_API_ERROR;
        };
    }
}
