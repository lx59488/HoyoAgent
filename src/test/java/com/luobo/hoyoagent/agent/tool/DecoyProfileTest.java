package com.luobo.hoyoagent.agent.tool;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 冒烟脚本的「诱饵模式」只有在 decoy profile 真的把工具注册进来时才有效。
 * 否则 {@code scripts/smoke-agent.ps1} 第 5~8 条会以一个恒真的方式"通过"，
 * 而你以为自己测了能力边界 —— 这是"测试的测试"，比业务测试更容易骗人。
 *
 * <p>同时兜住另一类故障：工具重名会让整个 ApplicationContext 起不来
 * （{@code IllegalConfigurationException: Duplicated definition for tool}）。
 * 删源文件忘了 clean、留下旧的 .class 就会触发，2026-11-07 真实踩过一次。
 */
@SpringBootTest
@ActiveProfiles("decoy")
class DecoyProfileTest {

    @Autowired
    ApplicationContext ctx;

    @Test
    void decoyProfileRegistersDecoyTools() {
        assertThat(ctx.getBeansOfType(DecoyTool.class)).hasSize(1);
        // 诱饵在、真工具也在，两者同时挂到同一个 AiService 上才是被测的真实局面
        assertThat(ctx.getBeansOfType(CheckinTool.class)).hasSize(1);
    }
}
