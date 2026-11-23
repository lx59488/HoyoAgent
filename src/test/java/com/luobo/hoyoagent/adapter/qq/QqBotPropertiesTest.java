package com.luobo.hoyoagent.adapter.qq;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置口径：maxReplies 受平台硬上限约束，apiBase 有默认值。
 *
 * <p>谁能用机器人由 QQ 开放平台侧配置，代码里不再有 openid 白名单。
 */
class QqBotPropertiesTest {

    @Test
    void maxRepliesIsClampedToPlatformCeiling() {
        assertThat(props(0).maxReplies()).isEqualTo(4);
        assertThat(props(9).maxReplies()).isEqualTo(4);
        assertThat(props(2).maxReplies()).isEqualTo(2);
    }

    @Test
    void blankApiBaseFallsBackToProduction() {
        assertThat(new QqBotProperties(false, "", "", "", 4, 600).apiBase())
                .isEqualTo("https://api.sgroup.qq.com");
        assertThat(new QqBotProperties(false, "", "", " https://sandbox.api.sgroup.qq.com ", 4, 600).apiBase())
                .isEqualTo("https://sandbox.api.sgroup.qq.com");
    }

    private static QqBotProperties props(int maxReplies) {
        return new QqBotProperties(true, "app-id", "secret",
                "https://api.sgroup.qq.com", maxReplies, 600);
    }
}
