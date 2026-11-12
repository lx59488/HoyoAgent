package com.luobo.hoyoagent.mihoyo.transport;

import com.luobo.hoyoagent.mihoyo.config.HoyoProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MihoyoHttpTest {

    private MockWebServer server;
    private MihoyoHttp http;

    @BeforeEach
    void setUp() {
        server = new MockWebServer();
        HoyoProperties props = new HoyoProperties(Map.of(), Duration.ofSeconds(5), null);
        http = new MihoyoHttp(props, new ObjectMapper());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void returnsRetcodeAndHeadersInsteadOfThrowing() {
        server.enqueue(new MockResponse()
                .setBody("{\"retcode\":-3501,\"message\":\"expired\",\"data\":null}")
                .addHeader("Set-Cookie", "ltoken_v2=abc; Path=/"));

        MihoyoResult result = http.send(MihoyoRequest.get(server.url("/x").toString(), Map.of(), Map.of()));

        assertThat(result.ok()).isFalse();                       // 关键：不抛异常
        assertThat(result.retcode()).isEqualTo(-3501);
        assertThat(result.headers().values("Set-Cookie")).hasSize(1);
    }

    @Test
    void encodesQueryParameters() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"retcode\":0,\"data\":{}}"));
        http.send(MihoyoRequest.get(server.url("/info").toString(),
                Map.of("act_id", "e2023", "uid", "100"), Map.of()));

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).contains("act_id=e2023").contains("uid=100");
    }

    @Test
    void postFactoryReallySendsPost() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"retcode\":0,\"data\":{}}"));
        http.send(MihoyoRequest.post(server.url("/sign").toString(), "{\"uid\":\"1\"}", Map.of()));

        RecordedRequest req = server.takeRequest();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getBody().readUtf8()).isEqualTo("{\"uid\":\"1\"}");
    }
}