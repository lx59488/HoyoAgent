package com.luobo.hoyoagent.agent.tool;

import com.luobo.hoyoagent.agent.observability.ToolCallRecorder;
import com.luobo.hoyoagent.channel.SessionRegistry;
import com.luobo.hoyoagent.mihoyo.checkin.CheckinGame;
import com.luobo.hoyoagent.mihoyo.checkin.CheckinOverview;
import com.luobo.hoyoagent.mihoyo.checkin.CheckinOverviewService;
import com.luobo.hoyoagent.mihoyo.credential.AccountService;
import com.luobo.hoyoagent.mihoyo.credential.Credential;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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

    @Test
    void gameParamIsForwardedToService() {
        SessionRegistry sessions = new SessionRegistry();
        sessions.register("qqbot:c2c:AAA", "qq:AAA");
        Credential credential = Credential.of("ltoken=a; ltuid=1001");
        AccountService accounts = mock(AccountService.class);
        when(accounts.credentialOf("qq:AAA")).thenReturn(Optional.of(credential));
        CheckinOverviewService service = mock(CheckinOverviewService.class);
        when(service.overview("qq:AAA", credential, CheckinGame.STAR_RAIL)).thenReturn(new CheckinOverview(
                "1 个角色：已签 1",
                List.of(new CheckinOverview.Game("hkrpg_cn", "崩坏：星穹铁道", "1002",
                        "SIGNED", "今天已签", 11, 5)),
                List.of(), "2026-11-23"));

        CheckinOverviewTool tool = new CheckinOverviewTool(
                new ToolCallRecorder(), service, sessions, accounts);
        String reply = tool.getCheckinOverview("星铁", "qqbot:c2c:AAA");

        assertThat(reply).contains("- 崩坏：星穹铁道 1002：今天已签 · 累计 11 天 · 本月约漏 5 天（推算）");
        verify(service).overview("qq:AAA", credential, CheckinGame.STAR_RAIL);
    }

    /** 认不出的游戏名不能猜，也不能落到"全部"——直接把可选范围回给模型。 */
    @Test
    void unknownGameIsRejectedWithoutCallingService() {
        SessionRegistry sessions = new SessionRegistry();
        sessions.register("qqbot:c2c:AAA", "qq:AAA");
        AccountService accounts = mock(AccountService.class);
        CheckinOverviewService service = mock(CheckinOverviewService.class);

        CheckinOverviewTool tool = new CheckinOverviewTool(
                new ToolCallRecorder(), service, sessions, accounts);
        String reply = tool.getCheckinOverview("崩坏3", "qqbot:c2c:AAA");

        assertThat(reply).contains("查询签到总览失败").contains("崩坏3").contains("原神");
        verifyNoInteractions(service);
        verifyNoInteractions(accounts);
    }
}