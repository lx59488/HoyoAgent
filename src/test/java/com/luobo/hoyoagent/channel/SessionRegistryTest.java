package com.luobo.hoyoagent.channel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionRegistryTest {

    @Test
    void unknownSessionHasNoIdentity() {
        SessionRegistry registry = new SessionRegistry();

        assertThat(registry.userIdOf("web:abc")).isEmpty();
        assertThat(registry.userIdOf(null)).isEmpty();
    }

    @Test
    void registeredSessionKeepsPlatformUserId() {
        SessionRegistry registry = new SessionRegistry();
        registry.register("qqbot:c2c:AAA", "qq:AAA");

        assertThat(registry.userIdOf("qqbot:c2c:AAA")).contains("qq:AAA");
    }

    @Test
    void blankValuesAreIgnored() {
        SessionRegistry registry = new SessionRegistry();
        registry.register("", "qq:AAA");
        registry.register("qqbot:c2c:AAA", " ");

        assertThat(registry.userIdOf("qqbot:c2c:AAA")).isEmpty();
    }
}