package com.luobo.hoyoagent.agent.tool;

import com.luobo.hoyoagent.mihoyo.checkin.CheckinOverview;
import com.luobo.hoyoagent.mihoyo.checkin.CheckinRoleResult;
import com.luobo.hoyoagent.mihoyo.checkin.CheckinRunResult;
import com.luobo.hoyoagent.mihoyo.error.ErrorKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C5 · 工具输出契约：<b>工具给事实，模型给措辞</b>。
 *
 * <p>为什么需要这组断言：工具返回的文本会原样进模型上下文，模型又倾向于照抄。
 * 所以「Java 写的句子」「内部枚举」「装饰符号」一旦进了输出，就等于绕过 {@code <persona>}
 * 和 {@code <channel>} 把语气和格式写死在代码里。
 *
 * <p>四条都只查形状、不查具体文案——文案会变，形状不该变。
 */
class ToolOutputContractTest {

    private static final CheckinRunResult RUN = new CheckinRunResult(List.of(
            new CheckinRoleResult("hk4e_cn", "原神", "1001", "cn_gf01",
                    "SIGNED", "签到成功", 12, "原石×60", null),
            new CheckinRoleResult("hkrpg_cn", "崩坏：星穹铁道", "1002", "prod_gf_cn",
                    "ALREADY", "今天已签", 11, "", null),
            new CheckinRoleResult("nap_cn", "绝区零", "1003", "prod_gf_cn",
                    "FAILED", "登录状态失效", 0, "", ErrorKind.AUTH)),
            "3 个角色：新签 1，已签 1，失败 1");

    private static final CheckinOverview OVERVIEW = new CheckinOverview(
            "2 个角色：已签 1，未签 1",
            List.of(
                    new CheckinOverview.Game("hk4e_cn", "原神", "1001", "SIGNED", "今天已签", 12, 4),
                    new CheckinOverview.Game("hkrpg_cn", "崩坏：星穹铁道", "1002",
                            "UNSIGNED", "今天还没签", 11, 5)),
            List.of(new CheckinOverview.Recent("2026-11-16", "SCHEDULED", "DONE",
                    "3 个角色：新签 0，已签 3")),
            "2026-11-16");

    @Test
    void internalEnumsNeverReachTheModel() {
        for (String text : both()) {
            assertThat(text)
                    .as("内部枚举值必须先在工具里翻成人话")
                    .doesNotContain("SIGNED", "ALREADY", "FAILED", "UNSIGNED",
                            "DONE", "PARTIAL", "SKIPPED", "SCHEDULED", "STARTUP", "MANUAL");
        }
    }

    @Test
    void noDecorationsOrMarkdown() {
        for (String text : both()) {
            assertThat(text)
                    .as("QQ 不渲染 markdown，装饰符只会让模型照抄")
                    .doesNotContain("【", "】", "**", "|", "#", "```");
        }
    }

    @Test
    void derivedValuesAreMarked() {
        assertThat(CheckinOverviewTool.format(OVERVIEW))
                .as("漏签天数是推算出来的，模型得知道这一点才可能保住「约」")
                .contains("（推算）");
    }

    @Test
    void oneLinePerRole() {
        assertThat(dashLines(CheckinTool.format(RUN))).isEqualTo(3);
        assertThat(dashLines(CheckinOverviewTool.format(OVERVIEW))).isEqualTo(3);
    }

    private static long dashLines(String text) {
        return text.lines().filter(line -> line.startsWith("- ")).count();
    }

    private static List<String> both() {
        return List.of(CheckinTool.format(RUN), CheckinOverviewTool.format(OVERVIEW));
    }
}