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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 指定单个游戏时的过滤口径：只打这个游戏的接口，其它游戏一个请求都不发。
 *
 * <p>这是工具参数落到服务层的最后一公里——工具层测"有没有转发"，这里测"转发后有没有真的收窄"。
 */
class CheckinServiceTest {

    private static final Credential CRED = Credential.of("ltoken=a; ltuid=1001");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final GameRole GENSHIN = new GameRole("hk4e_cn", "cn_gf01", "1001", "旅行者", 60);

    private static final GameRole STAR_RAIL = new GameRole("hkrpg_cn", "prod_gf_cn", "1002", "开拓者", 70);

    @Test
    void onlyRequestedGameIsChecked() {
        GameRoleService roles = mock(GameRoleService.class);
        LunaClient luna = mock(LunaClient.class);
        when(roles.roles(CRED)).thenReturn(List.of(GENSHIN, STAR_RAIL));
        when(luna.info(CRED, CheckinGame.GENSHIN, "cn_gf01", "1001"))
                .thenReturn(info(false, 5), info(true, 6));
        when(luna.sign(CRED, CheckinGame.GENSHIN, "cn_gf01", "1001"))
                .thenReturn(result("{\"success\":0}"));
        when(luna.home(CRED, CheckinGame.GENSHIN)).thenReturn(result("{\"awards\":[]}"));

        CheckinRunResult out = new CheckinService(roles, luna, MAPPER)
                .checkinAll(CRED, CheckinGame.GENSHIN);

        assertThat(out.results()).singleElement().satisfies(role -> {
            assertThat(role.gameBiz()).isEqualTo("hk4e_cn");
            assertThat(role.status()).isEqualTo("SIGNED");
        });
        verify(luna, never()).info(any(), eq(CheckinGame.STAR_RAIL), any(), any());
        verify(luna, never()).sign(any(), eq(CheckinGame.STAR_RAIL), any(), any());
    }

    @Test
    void gameWithoutBoundRoleShortCircuits() {
        GameRoleService roles = mock(GameRoleService.class);
        LunaClient luna = mock(LunaClient.class);
        when(roles.roles(CRED)).thenReturn(List.of(GENSHIN));

        CheckinRunResult out = new CheckinService(roles, luna, MAPPER)
                .checkinAll(CRED, CheckinGame.ZZZ);

        assertThat(out.results()).isEmpty();
        assertThat(out.message()).contains("绝区零");
        verifyNoInteractions(luna);
    }

    /** 调度器走的一参重载必须仍然等于"全部"，别被过滤参数改了口径。 */
    @Test
    void nullFilterStillMeansAllGames() {
        GameRoleService roles = mock(GameRoleService.class);
        LunaClient luna = mock(LunaClient.class);
        when(roles.roles(CRED)).thenReturn(List.of(GENSHIN));
        when(luna.info(CRED, CheckinGame.GENSHIN, "cn_gf01", "1001")).thenReturn(info(true, 5));
        when(luna.home(CRED, CheckinGame.GENSHIN)).thenReturn(result("{\"awards\":[]}"));

        CheckinRunResult out = new CheckinService(roles, luna, MAPPER).checkinAll(CRED);

        assertThat(out.results()).singleElement()
                .extracting(CheckinRoleResult::gameBiz).isEqualTo("hk4e_cn");
    }

    /** 单游戏方法给批量流程和外部调用共用，必须能脱离"全部"直接跑。 */
    @Test
    void singleGameMethodRunsWithoutTheBatch() {
        GameRoleService roles = mock(GameRoleService.class);
        LunaClient luna = mock(LunaClient.class);
        when(luna.info(CRED, CheckinGame.GENSHIN, "cn_gf01", "1001")).thenReturn(info(true, 5));
        when(luna.home(CRED, CheckinGame.GENSHIN)).thenReturn(result("{\"awards\":[]}"));

        CheckinRoleResult out = new CheckinService(roles, luna, MAPPER).checkinGame(CRED, GENSHIN);

        assertThat(out.status()).isEqualTo("ALREADY");
        assertThat(out.totalDays()).isEqualTo(5);
        verifyNoInteractions(roles);
    }

    /** 一个游戏抛异常只能弄脏它自己：另外两个游戏的结论必须还在。 */
    @Test
    void oneBrokenGameDoesNotHideTheOthers() {
        GameRoleService roles = mock(GameRoleService.class);
        LunaClient luna = mock(LunaClient.class);
        when(roles.roles(CRED)).thenReturn(List.of(GENSHIN, STAR_RAIL));
        when(luna.info(CRED, CheckinGame.GENSHIN, "cn_gf01", "1001"))
                .thenThrow(new IllegalStateException("boom"));
        when(luna.info(CRED, CheckinGame.STAR_RAIL, "prod_gf_cn", "1002"))
                .thenReturn(info(false, 5), info(true, 6));
        when(luna.sign(CRED, CheckinGame.STAR_RAIL, "prod_gf_cn", "1002"))
                .thenReturn(result("{\"success\":0}"));
        when(luna.home(CRED, CheckinGame.STAR_RAIL)).thenReturn(result("{\"awards\":[]}"));

        CheckinRunResult out = new CheckinService(roles, luna, MAPPER).checkinAll(CRED);

        assertThat(out.results()).hasSize(2);
        assertThat(out.results().get(0).status()).isEqualTo("FAILED");
        assertThat(out.results().get(0).message()).contains("签到异常").contains("boom");
        assertThat(out.results().get(1).status()).isEqualTo("SIGNED");
    }

    private static MihoyoResult info(boolean signed, int days) {
        return result("{\"is_sign\":" + signed + ",\"total_sign_day\":" + days + "}");
    }

    private static MihoyoResult result(String json) {
        return new MihoyoResult(0, "OK", MAPPER.readTree(json), Headers.of());
    }
}
