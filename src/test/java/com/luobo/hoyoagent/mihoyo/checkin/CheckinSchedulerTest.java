package com.luobo.hoyoagent.mihoyo.checkin;

import com.luobo.hoyoagent.channel.notify.Notification;
import com.luobo.hoyoagent.channel.notify.Notifier;
import com.luobo.hoyoagent.mihoyo.credential.Account;
import com.luobo.hoyoagent.mihoyo.credential.AccountService;
import com.luobo.hoyoagent.mihoyo.credential.Credential;
import com.luobo.hoyoagent.mihoyo.error.ErrorKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 调度器的判定口径都在这里：
 * <ul>
 *   <li>全部新签/已签 = DONE；有成功有失败 = PARTIAL；一个没成 = FAILED（含"读不到角色"）</li>
 *   <li>今天已经 DONE 就不再发请求；MANUAL 才重跑</li>
 *   <li>一个账号都没绑 = 什么都不做</li>
 * </ul>
 */
class CheckinSchedulerTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final String CRON = "0 10 0 * * *";

    private static final String USER = "qq:A";

    private static final Credential CRED = Credential.of("ltoken=a; ltuid=100848866");

    private static final Account ACCOUNT = new Account(USER, CRED, Instant.now());

    @TempDir
    Path dir;

    private CheckinService checkin;

    private FileCheckinRecordStore store;

    private AccountService accounts;

    private Notifier notifier;

    private CheckinScheduler scheduler;

    @BeforeEach
    void setUp() {
        checkin = mock(CheckinService.class);
        store = new FileCheckinRecordStore(dir, JsonMapper.builder().build());
        accounts = mock(AccountService.class);
        when(accounts.all()).thenReturn(List.of(ACCOUNT));
        notifier = mock(Notifier.class);
        scheduler = new CheckinScheduler(checkin, store, accounts, notifier, true, CRON,
                "Asia/Shanghai", Duration.ZERO);
    }

    @Test
    void allSignedOrAlreadyIsDone() {
        when(checkin.checkinAll(any())).thenReturn(new CheckinRunResult(
                List.of(role("SIGNED"), role("ALREADY")), "2 个角色：新签 1，已签 1"));

        CheckinRecord record = runOnce(CheckinRecord.Trigger.SCHEDULED);

        assertThat(record.outcome()).isEqualTo(CheckinRecord.Outcome.DONE);
        assertThat(record.done()).isTrue();
        assertThat(record.attention()).isFalse();
        assertThat(record.kind()).isNull();
        assertThat(record.userId()).isEqualTo(USER);
        assertThat(record.trigger()).isEqualTo(CheckinRecord.Trigger.SCHEDULED);
        assertThat(store.find(USER, today())).contains(record);
    }

    @Test
    void mixedResultIsPartialAndReportsTheMostBlockingKind() {
        CheckinRoleResult authFailed = new CheckinRoleResult(
                "hkrpg_cn", "崩坏：星穹铁道", "103871907", "prod_gf_cn",
                "FAILED", "登录状态失效，请重新扫码绑定", 7, "", ErrorKind.AUTH);
        when(checkin.checkinAll(any())).thenReturn(new CheckinRunResult(
                List.of(role("SIGNED"), authFailed), "2 个角色：新签 1，失败/需处理 1"));

        CheckinRecord record = runOnce(CheckinRecord.Trigger.SCHEDULED);

        assertThat(record.outcome()).isEqualTo(CheckinRecord.Outcome.PARTIAL);
        assertThat(record.kind()).isEqualTo(ErrorKind.AUTH);
        assertThat(record.attention()).isTrue();
        assertThat(record.roles()).hasSize(2);
    }

    @Test
    void roleLoadFailureIsFailedEvenWithNoResults() {
        when(checkin.checkinAll(any())).thenReturn(new CheckinRunResult(
                List.of(), "读取绑定角色失败：登录状态失效，请重新扫码绑定", ErrorKind.AUTH));

        CheckinRecord record = runOnce(CheckinRecord.Trigger.SCHEDULED);

        assertThat(record.outcome()).isEqualTo(CheckinRecord.Outcome.FAILED);
        assertThat(record.kind()).isEqualTo(ErrorKind.AUTH);
        assertThat(record.roles()).isEmpty();
    }

    @Test
    void noAccountsMeansNothingToDo() {
        when(accounts.all()).thenReturn(List.of());

        assertThat(scheduler.runAll(CheckinRecord.Trigger.SCHEDULED)).isEmpty();
        verifyNoInteractions(checkin);
        assertThat(store.recent(USER, 7)).isEmpty();
    }

    @Test
    void scheduledRunSkipsWhenTodayAlreadyDone() {
        saveToday(CheckinRecord.Outcome.DONE);

        assertThat(scheduler.runAll(CheckinRecord.Trigger.SCHEDULED)).isEmpty();
        verify(checkin, never()).checkinAll(any());
    }

    @Test
    void scheduledRunRetriesWhenTodayWasOnlyPartial() {
        saveToday(CheckinRecord.Outcome.PARTIAL);
        when(checkin.checkinAll(any())).thenReturn(new CheckinRunResult(
                List.of(role("ALREADY")), "1 个角色：已签 1"));

        CheckinRecord record = runOnce(CheckinRecord.Trigger.SCHEDULED);

        assertThat(record.outcome()).isEqualTo(CheckinRecord.Outcome.DONE);
        verify(checkin).checkinAll(any());
    }

    @Test
    void manualRunRerunsEvenWhenTodayIsDone() {
        saveToday(CheckinRecord.Outcome.DONE);
        when(checkin.checkinAll(any())).thenReturn(new CheckinRunResult(
                List.of(role("ALREADY")), "1 个角色：已签 1"));

        CheckinRecord record = runOnce(CheckinRecord.Trigger.MANUAL);

        assertThat(record.trigger()).isEqualTo(CheckinRecord.Trigger.MANUAL);
        assertThat(record.outcome()).isEqualTo(CheckinRecord.Outcome.DONE);
        verify(checkin).checkinAll(any());
    }

    @Test
    void unexpectedExceptionBecomesFailedRecordInsteadOfCrashingTheJob() {
        when(checkin.checkinAll(any())).thenThrow(new IllegalStateException("socket closed"));

        CheckinRecord record = runOnce(CheckinRecord.Trigger.SCHEDULED);

        assertThat(record.outcome()).isEqualTo(CheckinRecord.Outcome.FAILED);
        assertThat(record.kind()).isEqualTo(ErrorKind.UNKNOWN);
        assertThat(record.message()).contains("socket closed");
    }

    @Test
    void disabledSchedulerSkipsEverything() {
        CheckinScheduler disabled = new CheckinScheduler(checkin, store, accounts, notifier, false,
                CRON, "Asia/Shanghai", Duration.ZERO);

        assertThat(disabled.runAll(CheckinRecord.Trigger.SCHEDULED)).isEmpty();
        assertThat(disabled.runAll(CheckinRecord.Trigger.STARTUP)).isEmpty();
        verify(checkin, never()).checkinAll(any());

        when(checkin.checkinAll(any())).thenReturn(new CheckinRunResult(
                List.of(role("SIGNED")), "1 个角色：新签 1"));
        assertThat(disabled.runAll(CheckinRecord.Trigger.MANUAL)).hasSize(1);
        verify(checkin).checkinAll(any());
    }

    @Test
    void recordFromAnotherUserDoesNotCountAsTodays() {
        store.save(new CheckinRecord("qq:B", today(), CheckinRecord.Trigger.SCHEDULED,
                CheckinRecord.Outcome.DONE, null, today() + "T00:10:00+08:00",
                today() + "T00:10:05+08:00", "别人的记录", List.of()));
        when(checkin.checkinAll(any())).thenReturn(new CheckinRunResult(
                List.of(role("SIGNED")), "1 个角色：新签 1"));

        CheckinRecord record = runOnce(CheckinRecord.Trigger.SCHEDULED);

        assertThat(record.userId()).isEqualTo(USER);
        verify(checkin).checkinAll(any());
    }

    @Test
    void nextRunRollsOverToTomorrowAfterTodaysTime() {
        ZonedDateTime afterCron = ZonedDateTime.of(2026, 11, 13, 9, 0, 0, 0, ZONE);
        ZonedDateTime beforeCron = ZonedDateTime.of(2026, 11, 13, 0, 5, 0, 0, ZONE);

        assertThat(scheduler.nextRun(afterCron)).isEqualTo("2026-11-14T00:10+08:00");
        assertThat(scheduler.nextRun(beforeCron)).isEqualTo("2026-11-13T00:10+08:00");
    }

    @Test
    void publishedNotificationCarriesTheOutcome() {
        when(checkin.checkinAll(any())).thenReturn(new CheckinRunResult(
                List.of(role("SIGNED")), "1 个角色：新签 1"));

        runOnce(CheckinRecord.Trigger.SCHEDULED);

        ArgumentCaptor<Notification> sent = ArgumentCaptor.forClass(Notification.class);
        verify(notifier).send(sent.capture());
        assertThat(sent.getValue().level()).isEqualTo(Notification.Level.INFO);
        assertThat(sent.getValue().title()).contains("完成");
        assertThat(sent.getValue().text()).contains("新签 1");
    }

    @Test
    void failedCheckinIsPublishedAsError() {
        when(checkin.checkinAll(any())).thenReturn(new CheckinRunResult(
                List.of(), "读取绑定角色失败：登录状态失效", ErrorKind.AUTH));

        runOnce(CheckinRecord.Trigger.SCHEDULED);

        ArgumentCaptor<Notification> sent = ArgumentCaptor.forClass(Notification.class);
        verify(notifier).send(sent.capture());
        assertThat(sent.getValue().level()).isEqualTo(Notification.Level.ERROR);
        assertThat(sent.getValue().title()).contains("失败");
    }

    @Test
    void notificationFailureDoesNotBreakTheCheckin() {
        when(checkin.checkinAll(any())).thenReturn(new CheckinRunResult(
                List.of(role("SIGNED")), "1 个角色：新签 1"));
        doThrow(new IllegalStateException("webhook down")).when(notifier).send(any());

        CheckinRecord record = runOnce(CheckinRecord.Trigger.SCHEDULED);

        assertThat(record.outcome()).isEqualTo(CheckinRecord.Outcome.DONE);
        assertThat(store.find(USER, today())).contains(record);
    }

    private CheckinRecord runOnce(CheckinRecord.Trigger trigger) {
        List<CheckinRecord> records = scheduler.runAll(trigger);
        assertThat(records).hasSize(1);
        return records.get(0);
    }

    private void saveToday(CheckinRecord.Outcome outcome) {
        store.save(new CheckinRecord(USER, today(), CheckinRecord.Trigger.SCHEDULED, outcome, null,
                today() + "T00:10:00+08:00", today() + "T00:10:05+08:00", "已跑过", List.of()));
    }

    private static String today() {
        return LocalDate.now(ZONE).toString();
    }

    private static CheckinRoleResult role(String status) {
        return new CheckinRoleResult("hk4e_cn", "原神", "100848866", "cn_gf01",
                status, "今天已经签过了", 12, "", null);
    }
}