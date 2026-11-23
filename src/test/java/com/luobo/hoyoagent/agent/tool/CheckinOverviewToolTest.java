package com.luobo.hoyoagent.agent.tool;

import com.luobo.hoyoagent.mihoyo.checkin.CheckinOverview;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 签到总览的输出形状。C5 之后这里同时是"格式回归"：字段顺序、枚举翻译、推算标记。
 */
class CheckinOverviewToolTest {

    @Test
    void formatShowsTodayAndRecent() {
        CheckinOverview overview = new CheckinOverview(
                "2 个角色：已签 1，未签 1",
                List.of(
                        new CheckinOverview.Game("hk4e_cn", "原神", "1001",
                                "SIGNED", "今天已签", 12, 4),
                        new CheckinOverview.Game("hkrpg_cn", "崩坏：星穹铁道", "1002",
                                "UNSIGNED", "今天还没签", 11, 5)),
                List.of(new CheckinOverview.Recent("2026-11-16", "SCHEDULED", "DONE",
                        "3 个角色：新签 0，已签 3")),
                "2026-11-16");

        String text = CheckinOverviewTool.format(overview);

        assertThat(text)
                .startsWith("今日 2026-11-16")
                .contains("- 原神 1001：今天已签 · 累计 12 天 · 本月约漏 4 天（推算）")
                .contains("- 崩坏：星穹铁道 1002：今天还没签 · 累计 11 天 · 本月约漏 5 天（推算）")
                .contains("最近")
                .contains("- 2026-11-16 定时任务：全部成功（3 个角色：新签 0，已签 3）")
                // C5：结论由模型给，枚举值不许出现在模型面前
                .doesNotContain("【结论】", "2 个角色：已签 1，未签 1", "DONE", "SCHEDULED");
    }

    @Test
    void formatStopsAfterTodayWhenNoRoles() {
        CheckinOverview overview = new CheckinOverview(
                "当前账号没有可签到的角色（原神/星铁/绝区零）",
                List.of(), List.of(), "2026-11-16");

        assertThat(CheckinOverviewTool.format(overview))
                .isEqualTo("今日 2026-11-16\n- 没有可查询的角色：当前账号没有可签到的角色（原神/星铁/绝区零）");
    }
}