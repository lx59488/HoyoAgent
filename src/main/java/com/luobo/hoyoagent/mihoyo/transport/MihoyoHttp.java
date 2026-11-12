package com.luobo.hoyoagent.mihoyo.transport;

import com.luobo.hoyoagent.common.exception.BusinessException;
import com.luobo.hoyoagent.common.exception.ErrorCode;
import com.luobo.hoyoagent.mihoyo.config.HoyoProperties;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;

/**
 * 唯一的 HTTP 管道。
 *
 * <p>刻意做成<b>无状态</b>：headers 随 {@link MihoyoResult} 返回，不存字段。
 * （旧实现用 volatile 字段存"上一次响应"，并发时会把别人的 Set-Cookie 串进来。）
 *
 * <p>这里只做三件事：发请求、解析 retcode、包装 IO 异常。
 * retcode 的业务含义交给 {@code MihoyoError}，本类不判断"是不是登录失效"。
 */
@Component
public class MihoyoHttp {

    private static final Logger log = LoggerFactory.getLogger(MihoyoHttp.class);

    private static final MediaType JSON = MediaType.get("application/json;charset=UTF-8");

    private final OkHttpClient client;

    private final ObjectMapper mapper;

    public MihoyoHttp(HoyoProperties props, ObjectMapper mapper) {
        Duration timeout = props.timeout() == null ? Duration.ofSeconds(15) : props.timeout();
        this.client = new OkHttpClient.Builder()
                .connectTimeout(timeout)
                .readTimeout(timeout)
                .build();
        this.mapper = mapper;
    }

    public MihoyoResult send(MihoyoRequest req) {
        HttpUrl.Builder url = HttpUrl.get(req.url()).newBuilder();
        req.query().forEach(url::addQueryParameter);

        Request.Builder builder = new Request.Builder().url(url.build());
        req.headers().forEach(builder::header);

        // method 必须被真正使用，否则 MihoyoRequest 里的字段就是摆设
        if ("POST".equalsIgnoreCase(req.method())) {
            builder.post(RequestBody.create(req.body() == null ? "" : req.body(), JSON));
        } else {
            builder.get();
        }

        try (Response response = client.newCall(builder.build()).execute()) {
            String text = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new BusinessException(ErrorCode.MOYO_API_ERROR, "米游社 HTTP " + response.code());
            }
            JsonNode root = mapper.readTree(text);
            int retcode = root.path("retcode").asInt(-1);
            if (retcode != 0) {
                // retcode 是整数，可以进日志；message 是自由文本，不进
                log.warn("米游社返回非 0: path={} retcode={}", url.build().encodedPath(), retcode);
            }
            return new MihoyoResult(retcode, root.path("message").asString(""),
                    root.path("data"), response.headers());
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.MOYO_NETWORK_ERROR, "米游社请求失败", e);
        }
    }
}
