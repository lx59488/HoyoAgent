package com.luobo.hoyoagent.mihoyo.checkin;

import com.luobo.hoyoagent.mihoyo.credential.Credential;
import com.luobo.hoyoagent.mihoyo.role.GameRole;
import com.luobo.hoyoagent.mihoyo.role.GameRoleService;
import com.luobo.hoyoagent.mihoyo.transport.MihoyoResult;
import okhttp3.Headers;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 总览指定单个游戏时：只读这个游戏的状态；其它游戏连 luna/info 都不发。
 *
 * <p>「最近 7 天」故意不跟着过滤——它是账号整体的历史记录，测试把这个边界也钉住。
 */
class CheckinOverviewServiceGameTest {

    private static final String USER = "qq:AAA";

    private static final Credential CRED = Credential.of("ltoken=a; ltuid=1001");

    private static final GameRole GENSHIN = new GameRole("hk4e_cn", "cn_gf01", "1001", "旅行者", 60);

    private static final GameRole STAR_RAIL = new GameRole("hkrpg_cn", "prod_gf_cn", "1002", "开拓者", 70);

    @Test
    void onlyRequestedGameIsRead() {
        GameRoleService roles = mock(GameRoleService.class);
        LunaClient luna = mock(LunaClient.class);
        CheckinRecordStore records = mock(CheckinRecordStore.class);
        when(roles.roles(CRED)).thenReturn(List.of(GENSHIN, STAR_RAIL));
        when(luna.info(CRED, CheckinGame.STAR_RAIL, "prod_gf_cn", "1002"))
                .thenReturn(result("{\"is_sign\":true,\"total_sign_day\":11}"));
        when(records.recent(anyString(), eq(7))).thenReturn(List.of(new CheckinRecord(
                USER, "2026-11-22", CheckinRecord.Trigger.SCHEDULED, CheckinRecord.Outcome.DONE,
                null, "2026-11-22T00:10:00+08:00", "2026-11-22T00:10:30+08:00",
                "3 个角色：新签 0，已签 3", List.of())));

        CheckinOverview out = service(roles, luna, records).overview(USER, CRED, CheckinGame.STAR_RAIL);

        assertThat(out.games()).singleElement().satisfies(game -> {
            assertThat(game.gameBiz()).isEqualTo("hkrpg_cn");
            assertThat(game.status()).isEqualTo("SIGNED");
            assertThat(game.totalDays()).isEqualTo(11);
        });
        // 最近 7 天是账号整体记录：指定单个游戏也不过滤
        assertThat(out.recent()).singleElement().satisfies(recent -> {
            assertThat(recent.date()).isEqualTo("2026-11-22");
            assertThat(recent.message()).contains("3 个角色");
        });
        verify(luna, never()).info(any(), eq(CheckinGame.GENSHIN), any(), any());
    }

    @Test
    void gameWithoutBoundRoleShortCircuits() {
        GameRoleService roles = mock(GameRoleService.class);
        LunaClient luna = mock(LunaClient.class);
        CheckinRecordStore records = mock(CheckinRecordStore.class);
        when(roles.roles(CRED)).thenReturn(List.of(GENSHIN));
        when(records.recent(anyString(), eq(7))).thenReturn(List.of());

        CheckinOverview out = service(roles, luna, records).overview(USER, CRED, CheckinGame.ZZZ);

        assertThat(out.games()).isEmpty();
        assertThat(out.message()).contains("绝区零");
        verifyNoInteractions(luna);
    }

    private static CheckinOverviewService service(GameRoleService roles, LunaClient luna, CheckinRecordStore records) {
        return new CheckinOverviewService(roles, luna, records, new ObjectMapper(), "Asia/Shanghai");
    }

    private static MihoyoResult result(String json) {
        return new MihoyoResult(0, "OK", new ObjectMapper().readTree(json), Headers.of());
    }
}
