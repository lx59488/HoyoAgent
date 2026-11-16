package com.luobo.hoyoagent.agent.tool;

import com.luobo.hoyoagent.mihoyo.checkin.CheckinOverview;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
                .contains("【结论】2 个角色：已签 1，未签 1")
                .contains("原神（1001）：今天已签 · 累计 12 天 · 本月约漏 4 天")
                .contains("崩坏：星穹铁道（1002）：今天还没签 · 累计 11 天 · 本月约漏 5 天")
                .contains("2026-11-16 DONE（SCHEDULED）：3 个角色：新签 0，已签 3");
    }

    @Test
    void formatStopsAfterConclusionWhenNoRoles() {
        CheckinOverview overview = new CheckinOverview(
                "当前账号没有可签到的角色（原神/星铁/绝区零）",
                List.of(), List.of(), "2026-11-16");

        assertThat(CheckinOverviewTool.format(overview))
                .isEqualTo("【结论】当前账号没有可签到的角色（原神/星铁/绝区零）");
    }
}