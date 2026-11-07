package com.luobo.hoyoagent.mihoyo;

import com.luobo.hoyoagent.mihoyo.api.MiyousheApi;
import com.luobo.hoyoagent.mihoyo.config.HoyoProperties;
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

    @Autowired
    HoyoProperties props;

    @Test
    void getAppConfig() {
        JsonNode data = api.getAppConfig(2);
        assertThat(data).isNotNull();
        assertThat(data.path("config")).isNotEmpty();
        System.out.println(data.path("config").toPrettyString());
    }

    @Test
    void getCharacters() {
        JsonNode list = api.getCharacters(props.roleId(), props.server()).path("list");
        assertThat(list).isNotEmpty();
        list.forEach(c -> System.out.println(c.path("name").asString() + " Lv." + c.path("level").asInt()));
    }
}
