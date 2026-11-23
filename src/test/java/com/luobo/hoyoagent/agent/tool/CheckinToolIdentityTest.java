package com.luobo.hoyoagent.agent.tool;

import com.luobo.hoyoagent.agent.observability.ToolCallRecorder;
import com.luobo.hoyoagent.channel.SessionRegistry;
import com.luobo.hoyoagent.mihoyo.checkin.CheckinGame;
import com.luobo.hoyoagent.mihoyo.checkin.CheckinRunResult;
import com.luobo.hoyoagent.mihoyo.checkin.CheckinService;
import com.luobo.hoyoagent.mihoyo.credential.AccountService;
import com.luobo.hoyoagent.mihoyo.credential.Credential;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CheckinToolIdentityTest {

    private static final Credential A = Credential.of("ltoken=a; ltuid=1001");

    private static final Credential B = Credential.of("ltoken=b; ltuid=1002");

    @Test
    void credentialComesFromRegisteredSession() {
        SessionRegistry sessions = new SessionRegistry();
        sessions.register("qqbot:c2c:AAA", "qq:AAA");
        sessions.register("qqbot:c2c:BBB", "qq:BBB");

        AccountService accounts = mock(AccountService.class);
        when(accounts.requireCredential("qq:AAA")).thenReturn(A);
        when(accounts.requireCredential("qq:BBB")).thenReturn(B);

        CheckinService checkin = mock(CheckinService.class);
        when(checkin.checkinAll(A, null)).thenReturn(new CheckinRunResult(List.of(), "A 的结果"));
        when(checkin.checkinAll(B, null)).thenReturn(new CheckinRunResult(List.of(), "B 的结果"));

        CheckinTool tool = new CheckinTool(new ToolCallRecorder(), checkin, sessions, accounts);

        assertThat(tool.checkinToday(null, "qqbot:c2c:AAA")).contains("A 的结果");
        assertThat(tool.checkinToday(null, "qqbot:c2c:BBB")).contains("B 的结果");

        verify(checkin).checkinAll(A, null);
        verify(checkin).checkinAll(B, null);
        verify(accounts, never()).requireCredential("local");
    }

    @Test
    void unknownSessionDoesNotPickAnyAccount() {
        SessionRegistry sessions = new SessionRegistry();
        AccountService accounts = mock(AccountService.class);
        CheckinService checkin = mock(CheckinService.class);

        CheckinTool tool = new CheckinTool(new ToolCallRecorder(), checkin, sessions, accounts);
        String reply = tool.checkinToday(null, "web:whatever");

        assertThat(reply).contains("没有绑定");
        verifyNoInteractions(accounts);
        verifyNoInteractions(checkin);
    }

    @Test
    void oneShotSessionHasNoIdentityEither() {
        SessionRegistry sessions = new SessionRegistry();
        AccountService accounts = mock(AccountService.class);
        CheckinService checkin = mock(CheckinService.class);

        CheckinTool tool = new CheckinTool(new ToolCallRecorder(), checkin, sessions, accounts);

        assertThat(tool.checkinToday(null, "one-shot:abc")).contains("没有绑定");
        verifyNoInteractions(accounts);
    }

    @Test
    void oneGameIsForwardedAsFilter() {
        SessionRegistry sessions = new SessionRegistry();
        sessions.register("qqbot:c2c:AAA", "qq:AAA");
        AccountService accounts = mock(AccountService.class);
        when(accounts.requireCredential("qq:AAA")).thenReturn(A);
        CheckinService checkin = mock(CheckinService.class);
        when(checkin.checkinAll(A, CheckinGame.GENSHIN))
                .thenReturn(new CheckinRunResult(List.of(), "原神的结果"));

        CheckinTool tool = new CheckinTool(new ToolCallRecorder(), checkin, sessions, accounts);
        String reply = tool.checkinToday("原神", "qqbot:c2c:AAA");

        assertThat(reply).contains("原神的结果");
        verify(checkin).checkinAll(A, CheckinGame.GENSHIN);
        verify(checkin, never()).checkinAll(A, CheckinGame.STAR_RAIL);
    }

    @Test
    void allIsForwardedAsNoFilter() {
        SessionRegistry sessions = new SessionRegistry();
        sessions.register("qqbot:c2c:AAA", "qq:AAA");
        AccountService accounts = mock(AccountService.class);
        when(accounts.requireCredential("qq:AAA")).thenReturn(A);
        CheckinService checkin = mock(CheckinService.class);
        when(checkin.checkinAll(A, null)).thenReturn(new CheckinRunResult(List.of(), "全部的结果"));

        CheckinTool tool = new CheckinTool(new ToolCallRecorder(), checkin, sessions, accounts);
        String reply = tool.checkinToday("全部", "qqbot:c2c:AAA");

        assertThat(reply).contains("全部的结果");
        verify(checkin).checkinAll(A, null);
    }

    /** 认不出的游戏名不能猜：既不查账号也不发签到请求，直接把可选范围回给模型。 */
    @Test
    void unknownGameIsRejectedBeforeTouchingCredentialOrNetwork() {
        SessionRegistry sessions = new SessionRegistry();
        sessions.register("qqbot:c2c:AAA", "qq:AAA");
        AccountService accounts = mock(AccountService.class);
        CheckinService checkin = mock(CheckinService.class);

        CheckinTool tool = new CheckinTool(new ToolCallRecorder(), checkin, sessions, accounts);
        String reply = tool.checkinToday("崩坏3", "qqbot:c2c:AAA");

        assertThat(reply).contains("签到失败").contains("崩坏3").contains("原神");
        verifyNoInteractions(checkin);
        verifyNoInteractions(accounts);
    }
}