package com.luobo.hoyoagent.mihoyo.auth.passport;

import com.luobo.hoyoagent.common.exception.BusinessException;
import com.luobo.hoyoagent.common.exception.ErrorCode;
import com.luobo.hoyoagent.mihoyo.client.DeviceIdProvider;
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
import java.util.List;
import java.util.Map;

/**
 * 米游社 passport 客户端。
 *
 * <p>这里有<b>两套互不通用</b>的请求头，混用会失败：
 *
 * <ul>
 *   <li><b>扫码系</b>（createQRLogin / queryQRLoginStatus）：client_type=3、app_version=2.90.1、
 *       DS2 salt={@code dDIQHbKOdaPaLuvQKVzUzqdeCaxjtaPV}，device_id 用 {@link PassportDevice}
 *       里持久化的 16 位 hex。</li>
 *   <li><b>换票系</b>（getLTokenBySToken / getCookieAccountInfoBySToken）：client_type=2、
 *       app_version=2.95.1、sdk=2.16.0、DS2 salt={@code JwYDpKvLj6MrMqqYU6jTKF17KNO2PXoS}，
 *       不需要 body。</li>
 * </ul>
 *
 * <p>实测（2026-11-12）：createQRLogin / queryQRLoginStatus 在本机 retcode=0；把 device_id 换掉再轮询会
 * 被 {@code -3503} 拒掉，所以两处必须复用同一个 device_id。
 */
@Component
public class PassportClient {

    private static final Logger log = LoggerFactory.getLogger(PassportClient.class);

    private static final String BASE = "https://passport-api.mihoyo.com";

    private static final String APP_ID = "bll8iq97cem8";

    /** 扫码系 salt，与 2.90.1 配对 */
    private static final String QR_SALT = "dDIQHbKOdaPaLuvQKVzUzqdeCaxjtaPV";

    /** 换票系 salt，与 2.95.1 配对 */
    private static final String AUTH_SALT = "JwYDpKvLj6MrMqqYU6jTKF17KNO2PXoS";

    private static final String QR_APP_VERSION = "2.90.1";

    private static final String AUTH_APP_VERSION = "2.95.1";

    private static final String AUTH_SDK_VERSION = "2.16.0";

    private static final String QR_UA = "Mozilla/5.0 miHoYoBBS/2.90.1 Capture/2.2.0";

    private static final String AUTH_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) miHoYoBBS/2.95.1";

    private final OkHttpClient httpClient;

    private final ObjectMapper mapper;

    private final PassportDevice device;

    private final DeviceIdProvider accountDevice;

    /** 最近一次响应的 Set-Cookie；v2 换取要从这里取，所以单独记一下 */
    private volatile List<String> lastSetCookies = List.of();

    public PassportClient(HoyoProperties props,
                          ObjectMapper mapper,
                          PassportDevice device,
                          DeviceIdProvider accountDevice) {
        this.mapper = mapper;
        this.device = device;
        this.accountDevice = accountDevice;
        // 扫码要等人扫，超时给足；否则 10 秒连接超时在弱网下会误报
        Duration timeout = props.timeout() == null || props.timeout().compareTo(Duration.ofSeconds(20)) < 0
                ? Duration.ofSeconds(20)
                : props.timeout();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(timeout)
                .readTimeout(timeout)
                .build();
    }

    /** 手机扫码用的登录二维码。 */
    public QrTicket createAppQrLogin() {
        JsonNode root = postQr("/account/ma-cn-passport/app/createQRLogin", "{}", "3", null);
        int retcode = root.path("retcode").asInt(-1);
        if (retcode != 0) {
            throw new BusinessException(ErrorCode.MOYO_API_ERROR, "创建登录二维码失败 retcode=" + retcode);
        }
        JsonNode data = root.path("data");
        String ticket = data.path("ticket").asString("");
        String url = data.path("url").asString("");
        if (ticket.isBlank() || url.isBlank()) {
            throw new BusinessException(ErrorCode.MOYO_API_ERROR, "创建登录二维码失败：响应缺少 ticket/url");
        }
        return new QrTicket(ticket, url);
    }

    /**
     * 轮询扫码状态。这里<b>不抛异常</b>：{@code -3501}/{@code -106} 就是"二维码过期"，
     * {@code -3503} 是风控，都是状态机的一部分，交给上层翻译。
     */
    public QrPoll queryAppQrLoginStatus(String ticket) {
        String body = mapper.writeValueAsString(Map.of("ticket", ticket));
        JsonNode root = postQr("/account/ma-cn-passport/app/queryQRLoginStatus", body, "3", null);
        JsonNode data = root.path("data");
        return new QrPoll(
                root.path("retcode").asInt(-1),
                root.path("message").asString(""),
                data.path("status").asString(""),
                data);
    }

    /** stoken + mid + stuid 换 ltoken。注意：拿到的是 **v1**，战绩接口可能不认，见 {@link #exchangeV2}。 */
    public TokenResult getLTokenByStoken(String stoken, String mid, String aid) {
        return exchange("/account/auth/api/getLTokenBySToken", "ltoken", stoken, mid, aid);
    }

    /** stoken + mid + stuid 换 cookie_token。 */
    public TokenResult getCookieTokenByStoken(String stoken, String mid, String aid) {
        return exchange("/account/auth/api/getCookieAccountInfoBySToken", "cookie_token", stoken, mid, aid);
    }

    /**
     * 用已有 stoken 刷出 {@code *_v2} cookie（ltoken_v2 / cookie_token_v2 / account_id_v2 …）。
     *
     * <p>原理：先建一张 <b>web</b> 登录二维码，然后我们自己冒充手机去 scan + confirm，
     * 最后查这张 web 码的状态——此时响应头 {@code Set-Cookie} 里就是一套全新的 v2 凭据。
     * 全程不需要用户参与（FufuLauncher 用同一招做"token 续期"）。
     *
     * <p>为什么必须做：{@code character/list} 这类战绩接口实测只认 v2。只拿 v1 会返回
     * 无 message 的 {@code 5003}，看起来像"没权限"。
     */
    public Map<String, String> exchangeV2(String stoken, String mid) {
        String webTicket = createWebQrTicket();
        String authCookie = "stoken=" + stoken + "; mid=" + mid;
        String body = mapper.writeValueAsString(Map.of(
                "ticket", webTicket,
                "token_types", List.of("4")));

        requireOk(postQr("/account/ma-cn-passport/app/scanQRLogin", body, "2", authCookie), "scanQRLogin");
        sleep(900);
        requireOk(postQr("/account/ma-cn-passport/app/confirmQRLogin", body, "2", authCookie), "confirmQRLogin");

        for (int attempt = 0; attempt < 3; attempt++) {
            JsonNode root = postQr("/account/ma-cn-passport/web/queryQRLoginStatus",
                    mapper.writeValueAsString(Map.of("ticket", webTicket)), "2", null);
            int retcode = root.path("retcode").asInt(-1);
            String status = root.path("data").path("status").asString("");
            if (retcode == 0 && "confirmed".equalsIgnoreCase(status)) {
                Map<String, String> cookies = parseSetCookies(lastSetCookies);
                if (!cookies.isEmpty()) {
                    log.info("v2 换取成功，拿到 {} 个 cookie 字段", cookies.size());
                    return cookies;
                }
                throw new BusinessException(ErrorCode.MOYO_API_ERROR, "web 码已确认但没拿到 Set-Cookie，稍后重试");
            }
            sleep(1000);
        }
        throw new BusinessException(ErrorCode.MOYO_API_ERROR, "web 确认码未在预期时间内确认");
    }

    private String createWebQrTicket() {
        JsonNode root = postQr("/account/ma-cn-passport/web/createQRLogin", "{}", "2", null);
        int retcode = root.path("retcode").asInt(-1);
        String ticket = root.path("data").path("ticket").asString("");
        if (retcode != 0 || ticket.isBlank()) {
            throw new BusinessException(ErrorCode.MOYO_API_ERROR, "创建 web 确认码失败 retcode=" + retcode);
        }
        return ticket;
    }

    private static Map<String, String> parseSetCookies(List<String> headers) {
        Map<String, String> cookies = new LinkedHashMap<>();
        for (String header : headers) {
            String main = header.split(";", 2)[0];
            int i = main.indexOf('=');
            if (i > 0) {
                cookies.put(main.substring(0, i).trim(), main.substring(i + 1).trim());
            }
        }
        return cookies;
    }

    private static void requireOk(JsonNode root, String step) {
        int retcode = root.path("retcode").asInt(-1);
        if (retcode != 0) {
            throw new BusinessException(ErrorCode.MOYO_API_ERROR, step + " 失败 retcode=" + retcode);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private TokenResult exchange(String path, String field, String stoken, String mid, String aid) {
        String cookie = "mid=" + mid + "; stoken=" + stoken + "; stuid=" + aid;
        JsonNode root = getAuth(path, cookie);
        int retcode = root.path("retcode").asInt(-1);
        String token = root.path("data").path(field).asString("");
        if (retcode != 0) {
            log.warn("passport 换票失败: path={} retcode={}", path, retcode);
        }
        return new TokenResult(retcode, root.path("message").asString(""), token);
    }

    // ---------------- HTTP ----------------

    private JsonNode postQr(String path, String body, String clientType, String cookie) {
        String url = BASE + path;
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("User-Agent", QR_UA)
                .header("Accept", "*/*")
                .header("Accept-Language", "zh-cn")
                .header("x-rpc-client_type", clientType)
                .header("x-rpc-app_version", QR_APP_VERSION)
                .header("x-rpc-device_id", device.deviceId())
                .header("x-rpc-device_fp", device.deviceFingerprint())
                .header("x-rpc-game_biz", "bbs_cn")
                .header("x-rpc-app_id", APP_ID)
                .header("x-rpc-sdk_version", QR_APP_VERSION)
                .header("x-rpc-account_version", QR_APP_VERSION)
                .header("x-rpc-device_model", "Mi 14")
                .header("x-rpc-device_name", "Mihoyo Capture")
                .header("DS", DsSigner.gen2(QR_SALT, body, ""))
                .post(RequestBody.create(body, MediaType.get("application/json;charset=UTF-8")));
        if (cookie != null && !cookie.isBlank()) {
            builder.header("Cookie", cookie);
        }
        return execute(builder.build(), path);
    }

    private JsonNode getAuth(String path, String cookie) {
        String url = BASE + path;
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", AUTH_UA)
                .header("Accept", "application/json")
                .header("Accept-Language", "zh-cn")
                .header("Cookie", cookie)
                .header("x-rpc-aigis", "")
                .header("x-rpc-app_id", APP_ID)
                .header("x-rpc-app_version", AUTH_APP_VERSION)
                .header("x-rpc-client_type", "2")
                .header("x-rpc-device_id", accountDevice.get())
                .header("x-rpc-device_name", "")
                .header("x-rpc-game_biz", "bbs_cn")
                .header("x-rpc-sdk_version", AUTH_SDK_VERSION)
                .header("DS", DsSigner.gen2(AUTH_SALT, "", ""))
                .get()
                .build();
        return execute(request, path);
    }

    private JsonNode execute(Request request, String path) {
        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            lastSetCookies = response.headers().values("Set-Cookie");
            if (!response.isSuccessful()) {
                throw new BusinessException(ErrorCode.MOYO_API_ERROR, "passport HTTP 状态异常: " + response.code());
            }
            JsonNode root = mapper.readTree(body);
            if (root == null || !root.isObject()) {
                throw new BusinessException(ErrorCode.MOYO_API_ERROR, "passport 响应不是 JSON 对象");
            }
            // retcode 是整数可以进日志；message 是自由文本，不进日志
            log.debug("passport 调用完成: path={} retcode={}", path, root.path("retcode").asInt(-1));
            return root;
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.MOYO_NETWORK_ERROR, "passport 请求失败", e);
        }
    }

    public record QrTicket(String ticket, String url) {
    }

    public record QrPoll(int retcode, String message, String status, JsonNode data) {
    }

    public record TokenResult(int retcode, String message, String token) {
        public boolean ok() {
            return retcode == 0 && token != null && !token.isBlank();
        }
    }
}
