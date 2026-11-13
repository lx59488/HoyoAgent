package com.luobo.hoyoagent.mihoyo.auth.passport;

import com.luobo.hoyoagent.common.exception.BusinessException;
import com.luobo.hoyoagent.common.exception.ErrorCode;
import com.luobo.hoyoagent.mihoyo.MihoyoEndpoints;
import com.luobo.hoyoagent.mihoyo.client.DeviceIdProvider;
import com.luobo.hoyoagent.mihoyo.credential.CookieJar;
import com.luobo.hoyoagent.mihoyo.enums.MiyousheHost;
import com.luobo.hoyoagent.mihoyo.transport.HeaderProfile;
import com.luobo.hoyoagent.mihoyo.transport.MihoyoHttp;
import com.luobo.hoyoagent.mihoyo.transport.MihoyoRequest;
import com.luobo.hoyoagent.mihoyo.transport.MihoyoResult;
import com.luobo.hoyoagent.mihoyo.transport.Profiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * 米游社 passport 客户端。
 *
 * <p>这里有<b>三套</b>互不通用的请求头（都收在 {@link Profiles} 里）：
 *
 * <ul>
 *   <li>{@link Profiles#QR_APP}：app 登录码，client_type=3；</li>
 *   <li>{@link Profiles#QR_WEB}：web 确认码 + 我们冒充手机 scan/confirm，client_type=2，
 *       <b>salt/版本仍是 2.90.1 那套</b>——这一步写错 client_type 会得到 -10001/-100 这类
 *       很难归因的拒绝；</li>
 *   <li>{@link Profiles#EXCHANGE}：换 ltoken / cookie_token / v2，client_type=2、app_version=2.95.1、
 *       另一套 salt。</li>
 * </ul>
 *
 * <p>与旧实现的区别：不再自带 OkHttp、也不再往实例字段里存"上一次响应的 Set-Cookie"。
 * 后者是并发隐患——两个 refresh 交错时，A 会把 B 的 cookie 取走。现在 Set-Cookie 直接从
 * {@link MihoyoResult#headers()} 里读，方法本身无状态。
 */
@Component
public class PassportClient {

    private static final Logger log = LoggerFactory.getLogger(PassportClient.class);

    private final MihoyoHttp http;

    private final ObjectMapper mapper;

    private final MihoyoEndpoints endpoints;

    private final PassportDevice device;

    private final DeviceIdProvider accountDevice;

    public PassportClient(MihoyoHttp http,
                          ObjectMapper mapper,
                          MihoyoEndpoints endpoints,
                          PassportDevice device,
                          DeviceIdProvider accountDevice) {
        this.http = http;
        this.mapper = mapper;
        this.endpoints = endpoints;
        this.device = device;
        this.accountDevice = accountDevice;
    }

    /** 手机扫码用的登录二维码。 */
    public QrTicket createAppQrLogin() {
        MihoyoResult result = send("/account/ma-cn-passport/app/createQRLogin",
                "{}", Profiles.QR_APP, null);
        requireOk(result, "创建登录二维码");

        String ticket = result.data().path("ticket").asString("");
        String url = result.data().path("url").asString("");
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
        MihoyoResult result = send("/account/ma-cn-passport/app/queryQRLoginStatus",
                body, Profiles.QR_APP, null);

        return new QrPoll(result.retcode(), result.message(),
                result.data().path("status").asString(""), result.data());
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
    public CookieJar exchangeV2(String stoken, String mid) {
        String webTicket = createWebQrTicket();
        String authCookie = "stoken=" + stoken + "; mid=" + mid;
        String body = mapper.writeValueAsString(Map.of(
                "ticket", webTicket,
                "token_types", List.of("4")));

        requireOk(send("/account/ma-cn-passport/app/scanQRLogin", body, Profiles.QR_WEB, authCookie),
                "scanQRLogin");
        sleep(900);
        requireOk(send("/account/ma-cn-passport/app/confirmQRLogin", body, Profiles.QR_WEB, authCookie),
                "confirmQRLogin");

        for (int attempt = 0; attempt < 3; attempt++) {
            MihoyoResult result = send("/account/ma-cn-passport/web/queryQRLoginStatus",
                    mapper.writeValueAsString(Map.of("ticket", webTicket)), Profiles.QR_WEB, null);

            if (result.ok() && "confirmed".equalsIgnoreCase(result.data().path("status").asString(""))) {
                CookieJar v2 = CookieJar.ofSetCookies(result.headers().values("Set-Cookie"));
                if (!v2.isEmpty()) {
                    log.info("v2 换取成功，拿到 {} 个 cookie 字段", v2.asMap().size());
                    return v2;
                }
                throw new BusinessException(ErrorCode.MOYO_API_ERROR, "web 码已确认但没拿到 Set-Cookie，稍后重试");
            }
            sleep(1000);
        }
        throw new BusinessException(ErrorCode.MOYO_API_ERROR, "web 确认码未在预期时间内确认");
    }

    private String createWebQrTicket() {
        MihoyoResult result = send("/account/ma-cn-passport/web/createQRLogin",
                "{}", Profiles.QR_WEB, null);

        String ticket = result.data().path("ticket").asString("");
        if (!result.ok() || ticket.isBlank()) {
            throw new BusinessException(ErrorCode.MOYO_API_ERROR,
                    "创建 web 确认码失败 retcode=" + result.retcode());
        }
        return ticket;
    }

    private TokenResult exchange(String path, String field, String stoken, String mid, String aid) {
        String cookie = "mid=" + mid + "; stoken=" + stoken + "; stuid=" + aid;
        MihoyoResult result = get(path, cookie);

        if (!result.ok()) {
            log.warn("passport 换票失败: path={} retcode={}", path, result.retcode());
        }
        return new TokenResult(result.retcode(), result.message(),
                result.data().path(field).asString(""));
    }

    // ---------------- HTTP ----------------

    private MihoyoResult send(String path, String body, HeaderProfile profile, String cookie) {
        Map<String, String> headers = profile.headers(
                cookie, device.deviceId(), device.deviceFingerprint(), body, "");
        return http.send(MihoyoRequest.post(
                endpoints.baseUrl(MiyousheHost.PASSPORT) + path, body, headers));
    }

    private MihoyoResult get(String path, String cookie) {
        Map<String, String> headers = Profiles.EXCHANGE.headers(
                cookie, accountDevice.get(), null, "", "");
        return http.send(MihoyoRequest.get(
                endpoints.baseUrl(MiyousheHost.PASSPORT) + path, Map.of(), headers));
    }

    private static void requireOk(MihoyoResult result, String step) {
        if (!result.ok()) {
            throw new BusinessException(ErrorCode.MOYO_API_ERROR,
                    step + " 失败 retcode=" + result.retcode());
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
