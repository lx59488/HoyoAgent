package com.luobo.hoyoagent.mihoyo.record;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 只测不需要身份（或只读角色）的路径。
 *
 * <p>注意：{@code character/list} 已于 2026-11-13 移除（无参考实现 + 账号侧 5003），
 * 所以这里不再有角色列表用例。
 */
@SpringBootTest
@Tag("network")
class RecordClientTest {

    @Autowired
    RecordClient recordClient;

    @Test
    void appConfigNeedsNoAuth() {
        JsonNode data = recordClient.getAppConfig(2);

        assertThat(data).isNotNull();
        assertThat(data.path("config")).isNotEmpty();
    }
}
