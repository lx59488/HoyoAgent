package com.luobo.hoyoagent.agent.tool;

import com.luobo.hoyoagent.agent.observability.ToolCallRecorder;
import com.luobo.hoyoagent.channel.SessionRegistry;
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
import static org.mockito.Mockito.when;

class CheckinToolIdentityTest {

    private static final Credential A = Credential.of("ltoken=a; ltuid=1001");

    private static final Credential B = Credential.of("ltoken=b; ltuid=1002");

    @Test
    void credentialComesFromMemoryIdOnly() {
        SessionRegistry sessions = new SessionRegistry("local");
        sessions.register("qqbot:c2c:AAA", "qq:AAA");
        sessions.register("qqbot:c2c:BBB", "qq:BBB");

        AccountService accounts = mock(AccountService.class);
        when(accounts.requireCredential("qq:AAA")).thenReturn(A);
        when(accounts.requireCredential("qq:BBB")).thenReturn(B);

        CheckinService checkin = mock(CheckinService.class);
        when(checkin.checkinAll(A)).thenReturn(new CheckinRunResult(List.of(), "A 的结果"));
        when(checkin.checkinAll(B)).thenReturn(new CheckinRunResult(List.of(), "B 的结果"));

        CheckinTool tool = new CheckinTool(new ToolCallRecorder(), checkin, sessions, accounts);

        assertThat(tool.checkinToday("qqbot:c2c:AAA")).contains("A 的结果");
        assertThat(tool.checkinToday("qqbot:c2c:BBB")).contains("B 的结果");

        verify(checkin).checkinAll(A);
        verify(checkin).checkinAll(B);
        verify(accounts, never()).requireCredential("local");
    }

    @Test
    void unknownSessionRunsAsLocal() {
        SessionRegistry sessions = new SessionRegistry("local");
        AccountService accounts = mock(AccountService.class);
        when(accounts.requireCredential("local")).thenReturn(A);

        CheckinService checkin = mock(CheckinService.class);
        when(checkin.checkinAll(A)).thenReturn(new CheckinRunResult(List.of(), "ok"));

        CheckinTool tool = new CheckinTool(new ToolCallRecorder(), checkin, sessions, accounts);
        tool.checkinToday("web:whatever");

        verify(accounts).requireCredential("local");
    }
}