package com.luobo.hoyoagent.adapter.qq;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 白名单的配置口径：
 * <ul>
 *   <li>条目去空白、丢空串——环境变量里多打一个逗号不该变成"有个空条目"；</li>
 *   <li>日志掩码不能把短 id 原样打出来；</li>
 *   <li>纯数字条目要能被识别成"填了 QQ 号"，启动时好提醒（见 QqBotGateway）。</li>
 * </ul>
 */
class QqBotPropertiesTest {

    @Test
    void allowlistIsTrimmedAndBlankEntriesDropped() {
        QqBotProperties props = props(" A1B2C3D4 , ,E5F6G7H8,  ");

        assertThat(props.allowlist()).containsExactly("A1B2C3D4", "E5F6G7H8");
    }

    @Test
    void duplicateEntriesAreKeptAsIs() {
        // 去重没意义（匹配是线性 contains），但别悄悄改用户的输入顺序
        QqBotProperties props = props("A1B2C3D4,A1B2C3D4");

        assertThat(props.allowlist()).containsExactly("A1B2C3D4", "A1B2C3D4");
    }

    @Test
    void maskKeepsFourOnEachSideAndHidesShortIdsEntirely() {
        assertThat(QqBotProperties.mask("1234567890ABCDEF")).isEqualTo("1234***CDEF");
        assertThat(QqBotProperties.mask("12345678")).isEqualTo("***");
        assertThat(QqBotProperties.mask("abc")).isEqualTo("***");
        assertThat(QqBotProperties.mask(null)).isEqualTo("***");
        assertThat(QqBotProperties.mask("  ")).isEqualTo("***");
    }

    @Test
    void qqNumberLookalikeIsPureDigitsOfPlausibleLength() {
        assertThat(QqBotProperties.looksLikeQqNumber("10001")).isTrue();
        assertThat(QqBotProperties.looksLikeQqNumber("123456789")).isTrue();
        assertThat(QqBotProperties.looksLikeQqNumber("123456789012")).isTrue();

        assertThat(QqBotProperties.looksLikeQqNumber("1234")).isFalse();              // 太短
        assertThat(QqBotProperties.looksLikeQqNumber("1234567890123")).isFalse();     // 太长
        assertThat(QqBotProperties.looksLikeQqNumber("A1B2C3D4E5F6")).isFalse();      // 有字母
        assertThat(QqBotProperties.looksLikeQqNumber(null)).isFalse();
    }

    @Test
    void maxRepliesIsClampedToPlatformCeiling() {
        assertThat(props("", 0).maxReplies()).isEqualTo(4);
        assertThat(props("", 9).maxReplies()).isEqualTo(4);
        assertThat(props("", 2).maxReplies()).isEqualTo(2);
    }

    private static QqBotProperties props(String allowlist) {
        return props(allowlist, 4);
    }

    private static QqBotProperties props(String allowlist, int maxReplies) {
        return new QqBotProperties(true, "app-id", "secret",
                List.of(allowlist.split(",", -1)), "https://api.sgroup.qq.com", maxReplies, 600);
    }
}
