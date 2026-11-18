package com.luobo.hoyoagent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "qq.enabled=false",
        "hoyo.memory.dir=target/test-memory"
})
class HoyoAgentApplicationTests {

    @Test
    void contextLoads() {
    }

}
