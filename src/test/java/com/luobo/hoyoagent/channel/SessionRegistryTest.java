package com.luobo.hoyoagent.channel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionRegistryTest {

    @Test
    void unknownSessionFallsBackToLocal() {
        SessionRegistry registry = new SessionRegistry("local");

        assertThat(registry.userIdOrLocal("web:abc")).isEqualTo("local");
        assertThat(registry.userIdOf("web:abc")).isEmpty();
    }

    @Test
    void registeredSessionWins() {
        SessionRegistry registry = new SessionRegistry("local");
        registry.register("qqbot:c2c:AAA", "qq:AAA");

        assertThat(registry.userIdOrLocal("qqbot:c2c:AAA")).isEqualTo("qq:AAA");
        assertThat(registry.userIdOf("qqbot:c2c:AAA")).contains("qq:AAA");
    }
}