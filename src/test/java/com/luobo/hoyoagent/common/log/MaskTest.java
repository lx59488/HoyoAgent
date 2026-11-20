package com.luobo.hoyoagent.common.log;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 脱敏是安全设施，规则必须锁住：
 * 太短的整串隐掉（否则"掩码"比原值还长）、长 id 只留首尾、URL/ticket 只留前缀。
 */
class MaskTest {

    @Test
    void idKeepsFourOnEachSideAndHidesShortValuesEntirely() {
        assertThat(Mask.id("1234567890ABCDEF")).isEqualTo("1234***CDEF");
        assertThat(Mask.id("123456789")).isEqualTo("1234***6789");
        assertThat(Mask.id("12345678")).isEqualTo("***");
        assertThat(Mask.id("abc")).isEqualTo("***");
        assertThat(Mask.id(null)).isEqualTo("***");
        assertThat(Mask.id("   ")).isEqualTo("***");
    }

    @Test
    void tailKeepsOnlyTheSuffix() {
        assertThat(Mask.tail("100848866", 4)).isEqualTo("***8866");
        assertThat(Mask.tail("1234", 4)).isEqualTo("***");
        assertThat(Mask.tail("12", 4)).isEqualTo("***");
        assertThat(Mask.tail(null, 4)).isEqualTo("***");
    }

    @Test
    void prefixKeepsOnlyTheHead() {
        assertThat(Mask.prefix("https://example.com/hook?token=abc", 12)).isEqualTo("https://exam…");
        assertThat(Mask.prefix("short", 12)).isEqualTo("***");
        assertThat(Mask.prefix(null, 12)).isEqualTo("***");
    }
}
