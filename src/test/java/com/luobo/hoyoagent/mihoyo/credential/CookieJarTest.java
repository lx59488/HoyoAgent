package com.luobo.hoyoagent.mihoyo.credential;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CookieJarTest {

    @Test
    void parsesRequestCookieHeader() {
        CookieJar jar = CookieJar.of("a=1; b=2");

        assertThat(jar.get("a")).contains("1");
        assertThat(jar.header()).isEqualTo("a=1; b=2");
    }

    @Test
    void setCookieIgnoresAttributes() {
        CookieJar jar = CookieJar.ofSetCookies(List.of(
                "ltoken_v2=abc; Path=/; Expires=Wed, 01 Jan 2027 00:00:00 GMT",
                "ltuid_v2=123; Path=/"));

        // Path/Expires 不能变成 cookie 字段
        assertThat(jar.asMap()).containsOnlyKeys("ltoken_v2", "ltuid_v2");
        assertThat(jar.header()).isEqualTo("ltoken_v2=abc; ltuid_v2=123");
    }

    @Test
    void mergeOverridesSameName() {
        CookieJar jar = CookieJar.of("ltoken=v1; stuid=1")
                .merge(CookieJar.of("ltoken_v2=v2; ltuid_v2=1"));

        assertThat(jar.get("ltoken")).contains("v1");
        assertThat(jar.get("ltoken_v2")).contains("v2");
        assertThat(jar.header()).contains("ltoken_v2=v2");
    }

    @Test
    void blankValuesAreIgnoredOnWrite() {
        CookieJar jar = CookieJar.empty().with("a", null).with("b", " ");

        assertThat(jar.isEmpty()).isTrue();
        assertThat(jar.header()).isEmpty();
    }
}
