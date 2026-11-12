package com.luobo.hoyoagent.mihoyo.transport;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 传输层的契约：调用方少传 query/headers 不该 NPE，method 必须真的被使用。
 * 这两条以前都是"写了但没生效"的坑。
 */
class MihoyoRequestTest {

    @Test
    void nullCollectionsBecomeEmpty() {
        MihoyoRequest request = new MihoyoRequest("GET", "https://example.com/x", null, null, null);

        assertThat(request.query()).isEmpty();
        assertThat(request.headers()).isEmpty();
    }

    @Test
    void postFactoryLeavesQueryEmpty() {
        MihoyoRequest request = MihoyoRequest.post("https://example.com/sign", "{}", Map.of());

        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.query()).isEmpty();
        assertThat(request.body()).isEqualTo("{}");
    }
}
