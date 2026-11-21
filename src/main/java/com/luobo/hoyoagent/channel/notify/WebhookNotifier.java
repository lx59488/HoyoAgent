package com.luobo.hoyoagent.channel.notify;

import com.luobo.hoyoagent.common.log.Mask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class WebhookNotifier implements Notifier{

    private static final Logger log = LoggerFactory.getLogger(WebhookNotifier.class);

    private final NotifyProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public WebhookNotifier(NotifyProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        if (props.enabled()) {
            log.info("[NOTIFY] 已启用 kind={} url={}", props.kind(), Mask.prefix(props.url(), 12));
        } else {
            log.info("[NOTIFY] 未启用（hoyo.notify.enabled=false）");
        }
    }
    @Override
    public void send(Notification notification) {
        if (!props.enabled() || props.url().isBlank()) {
            return;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(props.url()))
                    .timeout(props.timeout())
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            payload(props.kind(), notification), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 == 2) {
                log.info("[NOTIFY] 已推送 kind={} status={}", props.kind(), response.statusCode());
            } else {
                log.warn("[NOTIFY] 推送被拒 status={} body={}",
                        response.statusCode(), snippet(response.body()));
            }
        } catch (Exception e) {
            // 通知失败不影响任何业务：它只是旁路
            log.warn("[NOTIFY] 推送异常（不影响业务）: {}", e.toString());
        }
    }

    String payload(String kind, Notification notification) {
        Map<String, Object> body = switch (kind) {
            case "wecom" -> Map.of("msgtype", "text", "text",
                    Map.of("content", notification.title() + "\n" + notification.text()));
            case "feishu" -> Map.of("msg_type", "text", "content",
                    Map.of("text", notification.title() + "\n" + notification.text()));
            case "bark" -> {
                Map<String, Object> bark = new LinkedHashMap<>();
                bark.put("title", notification.title());
                bark.put("body", notification.text());
                bark.put("group", "HoyoAgent");
                if (notification.level() == Notification.Level.ERROR) {
                    bark.put("level", "timeSensitive");
                }
                yield bark;
            }
            default -> {
                Map<String, Object> generic = new LinkedHashMap<>();
                generic.put("title", notification.title());
                generic.put("text", notification.text());
                generic.put("level", notification.level().name());
                yield generic;
            }
        };
        return mapper.writeValueAsString(body);
    }

    private static String snippet(String body) {
        if (body == null) {
            return "(空)";
        }
        String one = body.replaceAll("\\s+", " ").trim();
        return one.length() <= 120 ? one : one.substring(0, 120) + "…";
    }
}
