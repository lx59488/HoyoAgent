package com.luobo.hoyoagent.mihoyo.checkin;

import com.luobo.hoyoagent.common.exception.BusinessException;
import com.luobo.hoyoagent.common.exception.ErrorCode;
import com.luobo.hoyoagent.mihoyo.auth.Credential;
import com.luobo.hoyoagent.mihoyo.auth.CredentialProvider;
import com.luobo.hoyoagent.mihoyo.config.HoyoProperties;
import com.luobo.hoyoagent.mihoyo.sign.DsSigner;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * luna 签到接口客户端（{@code info} / {@code sign} / {@code home}）。
 *
 * <p><b>2026-11-12 实测纠正</b>：签到认的是 {@code cookie_token} + {@code account_id}，
 * 不是 {@code stoken}。对照实验（同一账号、同一 uid、同一套头）：
 *
 * <pre>
 * cookie_token + account_id                     → retcode 0
 * stoken + mid + stuid                          → -100
 * ltoken + ltuid                                → -100
 * stoken + mid + stuid + ltoken + ltuid         → -100
 * 完整 cookie（含 cookie_token/account_id）      → retcode 0
 * </pre>
 *
 * <p>所以这里直接发<b>完整 cookie</b>：不挑字段，也不会因为某个字段缺失就悄悄失败。
 *
 * <p>另一个坑：{@code x-rpc-device_id} 取 {@code md5(完整 cookie)}（FufuLauncher 的做法），
 * 不是设备 UUID——签到接口不校验它，但保持一致能少一类"玄学失败"。
 */
@Component
public class LunaClient {

    private static final Logger log = LoggerFactory.getLogger(LunaClient.class);

    /** 签到专用 salt，与 2.93.1 配对；FufuLauncher 里叫 WebMihoyo */
    private static final String SALT = "G1ktdwFL4IyGkHuuWSmz0wUe9Db9scyK";

    private static final String APP_VERSION = "2.93.1";

    private static final String UA = "Mozilla/5.0 (Linux; Android 12; Unspecified Device) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Version/4.0 Chrome/103.0.5060.129 Mobile Safari/537.36 miHoYoBBS/2.93.1";

    private final OkHttpClient httpClient;

    private final ObjectMapper mapper;

    private final CredentialProvider credentials;

    public LunaClient(HoyoProperties props, ObjectMapper mapper, CredentialProvider credentials) {
        this.mapper = mapper;
        this.credentials = credentials;
        Duration timeout = props.timeout() == null ? Duration.ofSeconds(15) : props.timeout();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(timeout)
                .readTimeout(timeout)
                .build();
    }

    public Response info(MiyousheCheckinGame game, String region, String uid) {
        HttpUrl url = HttpUrl.get(game.url("/info")).newBuilder()
                .addQueryParameter("lang", "zh-cn")
                .addQueryParameter("act_id", game.actId())
                .addQueryParameter("region", region)
                .addQueryParameter("uid", uid)
                .build();
        return call(game, url, null);
    }

    public Response sign(MiyousheCheckinGame game, String region, String uid) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("act_id", game.actId());
        body.put("region", region);
        body.put("uid", uid);
        return call(game, HttpUrl.get(game.url("/sign")), mapper.writeValueAsString(body));
    }

    /** 奖励列表，用于把"今天拿了什么"说清楚；失败不影响签到结果。 */
    public Response home(MiyousheCheckinGame game) {
        HttpUrl url = HttpUrl.get(game.url("/home")).newBuilder()
                .addQueryParameter("lang", "zh-cn")
                .addQueryParameter("act_id", game.actId())
                .build();
        return call(game, url, null);
    }

    private Response call(MiyousheCheckinGame game, HttpUrl url, String jsonBody) {
        Credential credential = credentials.get();
        if (credential == null || credential.cookieHeader().isBlank()) {
            throw new BusinessException(ErrorCode.MOYO_AUTH_EXPIRED);
        }
        String cookie = credential.cookieHeader();

        Request.Builder req = new Request.Builder()
                .url(url)
                .header("Cookie", cookie)
                .header("DS", DsSigner.gen1(SALT))
                .header("x-rpc-device_id", DsSigner.md5(cookie))
                .header("x-rpc-client_type", "5")
                .header("x-rpc-app_version", APP_VERSION)
                .header("x-rpc-channel", "miyousheluodi")
                .header("X-Requested-With", "com.mihoyo.hyperion")
                .header("Referer", game.referer())
                .header("Origin", game.referer())
                .header("Accept", "application/json, text/plain, */*")
                .header("User-Agent", UA);
        if (game.signGame() != null) {
            req.header("x-rpc-signgame", game.signGame());
        }
        if (jsonBody == null) {
            req.get();
        } else {
            req.post(RequestBody.create(jsonBody, MediaType.get("application/json;charset=UTF-8")));
        }

        try (okhttp3.Response response = httpClient.newCall(req.build()).execute()) {
            String text = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new BusinessException(ErrorCode.MOYO_API_ERROR, "luna HTTP 状态异常: " + response.code());
            }
            JsonNode root = mapper.readTree(text);
            int retcode = root.path("retcode").asInt(-1);
            if (retcode != 0) {
                log.warn("luna 返回非 0: path={} retcode={}", url.encodedPath(), retcode);
            }
            return new Response(retcode, root.path("message").asString(""), root.path("data"));
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.MOYO_NETWORK_ERROR, "luna 请求失败", e);
        }
    }

    public record Response(int retcode, String message, JsonNode data) {

        public boolean ok() {
            return retcode == 0 && data != null && !data.isMissingNode();
        }
    }
}
