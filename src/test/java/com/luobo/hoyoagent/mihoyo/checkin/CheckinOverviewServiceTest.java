package com.luobo.hoyoagent.mihoyo.checkin;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CheckinOverviewServiceTest {

    @Test
    void missedEstimateCountsUnsignedDays() {
        assertThat(CheckinOverviewService.missedEstimate(12, 16)).isEqualTo(4);
        assertThat(CheckinOverviewService.missedEstimate(16, 16)).isZero();
    }

    @Test
    void missedEstimateNeverGoesNegative() {
        assertThat(CheckinOverviewService.missedEstimate(20, 16)).isZero();
        assertThat(CheckinOverviewService.missedEstimate(0, 1)).isEqualTo(1);
    }

    @Test
    void summarizeSeparatesSignedUnsignedAndAttention() {
        List<CheckinOverview.Game> games = List.of(
                new CheckinOverview.Game("hk4e_cn", "原神", "1", "SIGNED", "今天已签", 12, 4),
                new CheckinOverview.Game("hkrpg_cn", "星铁", "2", "UNSIGNED", "今天还没签", 11, 5),
                new CheckinOverview.Game("nap_cn", "绝区零", "3", "FAILED", "登录状态失效", 0, 0));

        assertThat(CheckinOverviewService.summarize(games))
                .isEqualTo("3 个角色：已签 1，未签 1，需处理 1");
    }
}