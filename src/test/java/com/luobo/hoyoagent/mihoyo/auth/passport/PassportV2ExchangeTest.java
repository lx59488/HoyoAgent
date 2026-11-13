package com.luobo.hoyoagent.mihoyo.auth.passport;

import com.luobo.hoyoagent.mihoyo.MihoyoEndpoints;
import com.luobo.hoyoagent.mihoyo.client.DeviceIdProvider;
import com.luobo.hoyoagent.mihoyo.config.HoyoProperties;
import com.luobo.hoyoagent.mihoyo.credential.CookieJar;
import com.luobo.hoyoagent.mihoyo.transport.MihoyoHttp;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v2 换取是整条登录链路里最难验的一段：4 次请求 + 从 Set-Cookie 取票。
 * 用 MockWebServer + host 覆盖（{@code hoyo.hosts.passport}）在本地把它锁住，不碰真实网络。
 */
class PassportV2ExchangeTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    private MockWebServer server;

    private PassportClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        // 去掉末尾斜杠，避免拼出 //account/...
        String base = server.url("/").toString().replaceAll("/+$", "");
        HoyoProperties props = new HoyoProperties(
                Map.of("passport", base),
                Duration.ofSeconds(5),
                "11111111-1111-1111-1111-111111111111");

        client = new PassportClient(
                new MihoyoHttp(props, mapper),
                mapper,
                new MihoyoEndpoints(props),
                new PassportDevice(tempDir.resolve("passport-device")),
                new DeviceIdProvider(props));
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void collectsV2CookiesFromSetCookieAndSendsStokenOnScan() throws Exception {
        server.enqueue(json("{\"retcode\":0,\"data\":{\"ticket\":\"web-1\"}}"));
        server.enqueue(json("{\"retcode\":0,\"data\":{}}"));
        server.enqueue(json("{\"retcode\":0,\"data\":{}}"));
        server.enqueue(json("{\"retcode\":0,\"data\":{\"status\":\"confirmed\"}}")
                .addHeader("Set-Cookie", "ltoken_v2=abc; Path=/; HttpOnly")
                .addHeader("Set-Cookie", "ltuid_v2=123; Path=/")
                .addHeader("Set-Cookie", "cookie_token_v2=ct; Path=/"));

        CookieJar v2 = client.exchangeV2("st-1", "mid-1");

        assertThat(v2.has("ltoken_v2")).isTrue();
        assertThat(v2.get("ltuid_v2")).contains("123");
        assertThat(v2.get("cookie_token_v2")).contains("ct");
        assertThat(v2.header()).doesNotContain("Path");

        RecordedRequest webCreate = server.takeRequest();
        RecordedRequest scan = server.takeRequest();
        RecordedRequest confirm = server.takeRequest();
        RecordedRequest query = server.takeRequest();

        assertThat(webCreate.getHeader("Cookie")).isNull();          // 建码不需要 stoken
        assertThat(scan.getHeader("Cookie")).isEqualTo("stoken=st-1; mid=mid-1");
        assertThat(confirm.getHeader("Cookie")).isEqualTo("stoken=st-1; mid=mid-1");
        assertThat(scan.getHeader("x-rpc-client_type")).isEqualTo("2");   // 冒充手机用 2
        assertThat(scan.getHeader("DS")).isNotBlank();
        assertThat(scan.getBody().readUtf8()).contains("\"ticket\":\"web-1\"");
        assertThat(query.getPath()).contains("web/queryQRLoginStatus");
    }

    private static MockResponse json(String body) {
        return new MockResponse().setBody(body);
    }
}
