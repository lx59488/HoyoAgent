package com.luobo.hoyoagent.mihoyo;

import com.luobo.hoyoagent.mihoyo.api.MiyousheApi;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Tag("network")
public class MiyousheApiTest {


    @Autowired
    MiyousheApi api;

    @Test
    void getAppConfig() {
        JsonNode data = api.getAppConfig(2);
        assertThat(data).isNotNull();
        assertThat(data.path("config")).isNotEmpty();
        System.out.println(data.path("config").toPrettyString());
    }
}
