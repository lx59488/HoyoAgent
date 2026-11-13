package com.luobo.hoyoagent.mihoyo.auth;

import com.luobo.hoyoagent.common.exception.BusinessException;
import com.luobo.hoyoagent.common.exception.ErrorCode;
import com.luobo.hoyoagent.mihoyo.auth.passport.PassportClient;
import com.luobo.hoyoagent.mihoyo.credential.CookieJar;
import com.luobo.hoyoagent.mihoyo.credential.Credential;
import com.luobo.hoyoagent.mihoyo.credential.CredentialProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 扫码登录状态机：创建二维码 → 轮询 → 确认后换票 → 落盘。
 *
 * <p>状态只往前走：{@code WAITING → SCANNED → BOUND}，异常分支是 {@code EXPIRED} / {@code FAILED}。
 * 换票只做一次，靠 {@link Session#complete} 里的同步块兜住并发轮询。
 *
 * <p>二维码 3 分钟过期（米游社自己的有效期约 5 分钟，这里留足余量）。
 */
@Service
public class QrLoginService {

    private static final Logger log = LoggerFactory.getLogger(QrLoginService.class);

    private static final Duration TTL = Duration.ofMinutes(3);

    /** 从 web 确认码响应里只收这些凭据字段 */
    private static final List<String> V2_KEYS = List.of(
            "ltoken_v2", "ltuid_v2", "cookie_token_v2", "account_id_v2", "ltmid_v2", "stoken_v2");

    private final PassportClient passport;

    private final CredentialProvider credentials;

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public QrLoginService(PassportClient passport, CredentialProvider credentials) {
        this.passport = passport;
        this.credentials = credentials;
    }

    public Start start() {
        purgeExpired();
        PassportClient.QrTicket ticket = passport.createAppQrLogin();
        Session session = new Session(ticket.ticket(), ticket.url(), Instant.now());
        sessions.put(ticket.ticket(), session);
        log.info("已创建登录二维码，ticket={}", shortTicket(ticket.ticket()));
        return new Start(ticket.ticket(), ticket.url(), TTL.toSeconds(), session.state);
    }

    public Status poll(String ticket) {
        Session session = sessions.get(ticket);
        if (session == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "未知的登录会话，请重新获取二维码");
        }
        if (session.isExpired()) {
            session.state = "EXPIRED";
            return session.status();
        }
        if (session.isFinished()) {
            return session.status();
        }

        PassportClient.QrPoll poll = passport.queryAppQrLoginStatus(ticket);
        switch (poll.retcode()) {
            case 0 -> handleOk(session, poll);
            case -3501, -106 -> {
                session.state = "EXPIRED";
                session.message = "二维码已过期，请重新获取";
            }
            case -3503 -> {
                session.state = "FAILED";
                session.message = "请求被风控拦截（-3503），换网络或稍后再试";
            }
            default -> {
                session.state = "FAILED";
                session.message = "米游社返回异常 retcode=" + poll.retcode();
                log.warn("扫码轮询失败: ticket={} retcode={}", shortTicket(ticket), poll.retcode());
            }
        }
        return session.status();
    }

    /** 给二维码图片用；会话不存在返回 null。 */
    public String qrUrl(String ticket) {
        Session session = sessions.get(ticket);
        return session == null ? null : session.url;
    }

    /**
     * 用当前 stoken 静默刷新 v2 凭据（不需要手机）。
     *
     * <p>典型场景：签到一直正常（v1 cookie_token 就够），但查角色突然 {@code 5003}——
     * 那就是 {@code ltoken_v2} 过期了，调一次这里即可。
     */
    public RefreshResult refreshV2() {
        Credential current = credentials.get();
        if (current == null || !current.hasStoken()
                || current.mid() == null || current.mid().isBlank()) {
            throw new BusinessException(ErrorCode.MOYO_AUTH_EXPIRED, "当前凭据缺少 stoken/mid，请重新扫码绑定");
        }
        CookieJar v2 = passport.exchangeV2(current.stoken(), current.mid());
        CookieJar merged = current.cookies().merge(v2);
        credentials.update(current.withCookies(merged));
        return new RefreshResult(merged.has("ltoken_v2"), merged.has("cookie_token_v2"),
                merged.has("ltoken_v2") ? "v2 凭据已刷新" : "刷新成功，但响应里没有 ltoken_v2");
    }

    private void handleOk(Session session, PassportClient.QrPoll poll) {
        String status = poll.status() == null ? "" : poll.status().toLowerCase();
        switch (status) {
            case "scanned" -> session.state = "SCANNED";
            case "confirmed" -> exchange(session, poll.data());
            case "created", "init", "" -> session.state = "WAITING";
            default -> session.state = "WAITING";
        }
    }

    private void exchange(Session session, JsonNode data) {
        String mid = data.path("user_info").path("mid").asString("");
        String aid = data.path("user_info").path("aid").asString("");
        String stoken = firstToken(data.path("tokens"));

        if (stoken.isBlank() || mid.isBlank()) {
            session.finish("FAILED", "扫码确认了，但响应里没有 stoken/mid");
            log.warn("扫码确认但缺少 stoken/mid，ticket={}", shortTicket(session.ticket));
            return;
        }

        PassportClient.TokenResult ltoken = passport.getLTokenByStoken(stoken, mid, aid);
        PassportClient.TokenResult cookieToken = passport.getCookieTokenByStoken(stoken, mid, aid);

        // v2 换取可能被风控拦；失败也先绑定，签到用 v1 cookie_token 就能跑
        CookieJar v2 = CookieJar.empty();
        try {
            v2 = passport.exchangeV2(stoken, mid);
        } catch (BusinessException e) {
            log.warn("v2 凭据换取失败（不影响签到，但战绩接口可能 5003）: {}", e.getMessage());
        }

        CookieJar jar = CookieJar.empty()
                .with("stoken", stoken).with("mid", mid).with("stuid", aid)
                .with("ltoken", ltoken.token()).with("ltuid", aid)
                .with("cookie_token", cookieToken.token()).with("account_id", aid)
                .merge(v2);
        credentials.update(new Credential(jar, aid, mid, stoken));

        session.uid = aid;
        session.mid = mid;
        session.hasStoken = true;
        session.hasLtoken = ltoken.ok();
        session.hasCookieToken = cookieToken.ok();
        // 字段先填完再切状态，避免并发轮询看到 BOUND 却还没 uid
        session.finish("BOUND", "登录成功");
        log.info("扫码登录成功: uid={} ltoken={} cookie_token={} v2={}",
                aid, ltoken.ok() ? "ok" : ("missing/" + ltoken.retcode()),
                cookieToken.ok() ? "ok" : ("missing/" + cookieToken.retcode()),
                v2.isEmpty() ? "missing" : "ok");
    }

    private static String firstToken(JsonNode tokens) {
        if (tokens == null || !tokens.isArray()) {
            return "";
        }
        for (JsonNode token : tokens) {
            String name = token.path("name").asString("");
            if ("stoken".equals(name) || "stoken_v2".equals(name)) {
                return token.path("token").asString("");
            }
        }
        // 个别响应只给 token_type=1（stoken）而不给 name
        for (JsonNode token : tokens) {
            if (token.path("token_type").asInt(-1) == 1) {
                return token.path("token").asString("");
            }
        }
        return "";
    }

    private void purgeExpired() {
        sessions.entrySet().removeIf(e -> e.getValue().isExpired());
    }

    private static String shortTicket(String ticket) {
        return ticket == null || ticket.length() < 8 ? "***" : ticket.substring(0, 8) + "…";
    }

    public record Start(String ticket, String url, long expiresInSeconds, String state) {
    }

    public record RefreshResult(boolean hasLtokenV2, boolean hasCookieTokenV2, String message) {
    }

    public record Status(String ticket,
                         String state,
                         String message,
                         long expiresInSeconds,
                         String uid,
                         String mid,
                         boolean hasStoken,
                         boolean hasLtoken,
                         boolean hasCookieToken) {
    }

    private static final class Session {

        private final String ticket;

        private final String url;

        private final Instant createdAt;

        private volatile String state = "WAITING";

        private volatile String message = "";

        private volatile String uid;

        private volatile String mid;

        private volatile boolean hasStoken;

        private volatile boolean hasLtoken;

        private volatile boolean hasCookieToken;

        private boolean exchanged;

        private Session(String ticket, String url, Instant createdAt) {
            this.ticket = ticket;
            this.url = url;
            this.createdAt = createdAt;
        }

        private boolean isExpired() {
            return Instant.now().isAfter(createdAt.plus(TTL));
        }

        private boolean isFinished() {
            return "BOUND".equals(state) || "FAILED".equals(state) || "EXPIRED".equals(state);
        }

        /** 并发轮询时只允许一次换票。 */
        private synchronized void finish(String state, String message) {
            if (exchanged) {
                return;
            }
            exchanged = true;
            this.state = state;
            this.message = message;
        }

        private Status status() {
            long remaining = Math.max(0, TTL.minus(Duration.between(createdAt, Instant.now())).toSeconds());
            String masked = mid == null || mid.isBlank()
                    ? null
                    : (mid.length() <= 4 ? "***" : "***" + mid.substring(mid.length() - 4));
            return new Status(ticket, state, message, remaining, uid, masked,
                    hasStoken, hasLtoken, hasCookieToken);
        }
    }
}
