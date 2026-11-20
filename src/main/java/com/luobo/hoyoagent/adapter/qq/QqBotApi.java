package com.luobo.hoyoagent.adapter.qq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class QqBotApi {

    private static final Logger log = LoggerFactory.getLogger(QqBotApi.class);

    private static final String TOKEN_URL = "https://bots.qq.com/app/getAppAccessToken";
    private static final Duration SKEW = Duration.ofSeconds(60);

    private final QqBotProperties props;
    private final HttpClient http;
    private final ObjectMapper mapper;

    private volatile String token;
    private volatile Instant expiresAt = Instant.EPOCH;

    public QqBotApi(QqBotProperties props, @Qualifier("qqHttpClient") HttpClient http, ObjectMapper mapper) {
        this.props = props;
        this.http = http;
        this.mapper = mapper;
    }

    /** QQBot <access_token>，提前 60 秒换，避免边界上刚好过期。 */
    public String accessToken() {
        String current = token;
        if (current != null && Instant.now().isBefore(expiresAt)) {
            return current;
        }
        synchronized (this) {
            if (token != null && Instant.now().isBefore(expiresAt)) {
                return token;
            }
            return refresh();
        }
    }

    private String refresh() {
        String body = mapper.writeValueAsString(Map.of(
                "appId", props.appId(),
                "clientSecret", props.clientSecret()));
        HttpResponse<String> resp = post(TOKEN_URL, null, body);
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("换 access_token 失败 HTTP " + resp.statusCode() + "：" + snippet(resp.body()));
        }
        JsonNode node = mapper.readTree(resp.body());
        String fresh = node.path("access_token").asString("");
        if (fresh.isBlank()) {
            throw new IllegalStateException("access_token 为空：" + snippet(resp.body()));
        }
        long expiresIn = node.path("expires_in").asLong(7200);   // 平台有时给字符串，asLong 会转
        token = fresh;
        expiresAt = Instant.now().plusSeconds(expiresIn).minus(SKEW);
        log.info("[QQ] access_token 已刷新，{}s 后过期", expiresIn);
        return fresh;
    }

    /**
     * 被动回复单聊。msgId 来自事件 d.id，5 分钟内有效；同一 msgId 下 msg_seq 必须递增，
     * 否则平台按重复消息丢弃（40054005）。
     *
     * @return HTTP 非 200 返回 {@code false}（只记日志）；网络异常仍会抛出，由调用方兜底。
     *         注意：这里发的是<b>被动回复</b>，不能当主动推送通道用（主动消息另有频控）。
     */
    public boolean sendC2C(String openid, String content, String msgId, int seq) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", content);
        payload.put("msg_type", 0);       // 0 = 纯文本
        payload.put("msg_id", msgId);
        payload.put("msg_seq", seq);

        String url = props.apiBase() + "/v2/users/" + openid + "/messages";
        String body = mapper.writeValueAsString(payload);
        HttpResponse<String> resp = post(url, accessToken(), body);

        if (resp.statusCode() == 401) {   // token 被提前作废：换一次再试
            log.warn("[QQ] 401，换 token 后重试");
            synchronized (this) {
                expiresAt = Instant.EPOCH;
            }
            resp = post(url, accessToken(), body);
        }
        if (resp.statusCode() != 200) {
            log.warn("[QQ] 发送失败 HTTP {}：{}", resp.statusCode(), snippet(resp.body()));
            return false;
        }
        return true;
    }

    /**
     * T0 验证用：把图片二进制直传给单聊富媒体接口，返回 file_info。
     *
     * <p>官方文档里 {@code file_data} 有 5MB 上限，我们的 PNG 只有几 KB。
     * 失败直接抛：调用方（探针）要把原因带回 QQ，不能静默。
     */
    public String uploadImage(String openid, byte[] png, String fileName) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("file_type", 1);            // 1 = 图片
        payload.put("file_name", fileName);
        payload.put("file_data", Base64.getEncoder().encodeToString(png));
        payload.put("srv_send_msg", false);     // 只上传，不占用主动消息

        String url = props.apiBase() + "/v2/users/" + openid + "/files";
        String body = mapper.writeValueAsString(payload);
        HttpResponse<String> resp = post(url, accessToken(), body);

        if (resp.statusCode() != 200) {
            throw new IllegalStateException("上传图片失败 HTTP " + resp.statusCode()
                    + "：" + snippet(resp.body()));
        }
        JsonNode node = mapper.readTree(resp.body());
        String fileInfo = node.path("file_info").asString("");
        if (fileInfo.isBlank()) {
            throw new IllegalStateException("上传图片未返回 file_info：" + snippet(resp.body()));
        }
        log.info("[QQ] 图片已上传，{} 字节，ttl={}s", png.length, node.path("ttl").asInt(-1));
        return fileInfo;
    }

    /**
     * 被动回复一张图片：先上传拿 file_info，再发 msg_type=7。
     *
     * <p>注意：{@code msg_type=7} 时 {@code content} 必须给个非空值（官方已知问题，填个空格）。
     */
    public boolean sendC2CImage(String openid, byte[] png, String msgId, int seq) {
        String fileName = "probe-" + System.currentTimeMillis() + ".png";
        String fileInfo = uploadImage(openid, png, fileName);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", " ");
        payload.put("msg_type", 7);             // 7 = 富媒体
        payload.put("media", Map.of("file_info", fileInfo));
        payload.put("msg_id", msgId);
        payload.put("msg_seq", seq);

        String url = props.apiBase() + "/v2/users/" + openid + "/messages";
        String body = mapper.writeValueAsString(payload);
        HttpResponse<String> resp = post(url, accessToken(), body);

        if (resp.statusCode() == 401) {
            log.warn("[QQ] 图片 401，换 token 后重试");
            synchronized (this) {
                expiresAt = Instant.EPOCH;
            }
            resp = post(url, accessToken(), body);
        }
        if (resp.statusCode() != 200) {
            log.warn("[QQ] 图片发送失败 HTTP {}：{}", resp.statusCode(), snippet(resp.body()));
            return false;
        }
        return true;
    }

    private HttpResponse<String> post(String url, String bearer, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (bearer != null) {
            builder.header("Authorization", "QQBot " + bearer);
        }
        try {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("请求 " + url + " 失败：" + e, e);
        }
    }

    private static String snippet(String body) {
        if (body == null) return "(空)";
        String one = body.replaceAll("\\s+", " ").trim();
        return one.length() <= 200 ? one : one.substring(0, 200) + "…";
    }
}