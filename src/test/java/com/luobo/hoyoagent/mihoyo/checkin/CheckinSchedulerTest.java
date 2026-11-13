package com.luobo.hoyoagent.mihoyo.checkin;

import com.luobo.hoyoagent.mihoyo.credential.CredentialProvider;
import com.luobo.hoyoagent.mihoyo.error.ErrorKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 调度器的判定口径都在这里：
 * <ul>
 *   <li>全部新签/已签 = DONE；有成功有失败 = PARTIAL；一个没成 = FAILED（含"读不到角色"）</li>
 *   <li>今天已经 DONE 就不再发请求；手动 force 才重跑</li>
 *   <li>没绑号 = SKIPPED，而且一次米游社都不打</li>
 * </ul>
 */
class CheckinSchedulerTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final String CRON = "0 10 0 * * *";

    @TempDir
    Path dir;

    private CheckinService checkin;

    private CheckinRecordStore store;

    private CredentialProvider credentials;

    private CheckinScheduler scheduler;

    @BeforeEach
    void setUp() {
        checkin = mock(CheckinService.class);
        store = new CheckinRecordStore(dir, JsonMapper.builder().build());
        credentials = mock(CredentialProvider.class);
        when(credentials.isPresent()).thenReturn(true);
        scheduler = new CheckinScheduler(checkin, store, credentials, "local", true, CRON,
                "Asia/Shanghai", Duration.ofSeconds(0));
    }

    @Test
    void allSignedOrAlreadyIsDone() {
        when(checkin.checkinAll()).thenReturn(new CheckinService.Summary(
                List.of(role("SIGNED"), role("ALREADY")), "2 个角色：新签 1，已签 1"));

        CheckinRecord record = scheduler.run(CheckinRecord.Trigger.SCHEDULED);

        assertThat(record.outcome()).isEqualTo(CheckinRecord.Outcome.DONE);
        assertThat(record.done()).isTrue();
        assertThat(record.attention()).isFalse();
        assertThat(record.kind()).isNull();
        assertThat(record.userId()).isEqualTo("local");
        assertThat(record.trigger()).isEqualTo(CheckinRecord.Trigger.SCHEDULED);
        assertThat(store.find(today())).contains(record);
    }

    @Test
    void mixedResultIsPartialAndReportsTheMostBlockingKind() {
        CheckinService.RoleResult authFailed = new CheckinService.RoleResult(
                "hkrpg_cn", "崩坏：星穹铁道", "103871907", "prod_gf_cn",
                "FAILED", "登录状态失效，请重新扫码绑定", 7, "", ErrorKind.AUTH);
        when(checkin.checkinAll()).thenReturn(new CheckinService.Summary(
                List.of(role("SIGNED"), authFailed), "2 个角色：新签 1，失败/需处理 1"));

        CheckinRecord record = scheduler.run(CheckinRecord.Trigger.SCHEDULED);

        assertThat(record.outcome()).isEqualTo(CheckinRecord.Outcome.PARTIAL);
        assertThat(record.kind()).isEqualTo(ErrorKind.AUTH);
        assertThat(record.attention()).isTrue();
        assertThat(record.roles()).hasSize(2);
    }

    @Test
    void roleLoadFailureIsFailedEvenWithNoResults() {
        when(checkin.checkinAll()).thenReturn(new CheckinService.Summary(
                List.of(), "读取绑定角色失败：登录状态失效，请重新扫码绑定", ErrorKind.AUTH));

        CheckinRecord record = scheduler.run(CheckinRecord.Trigger.SCHEDULED);

        assertThat(record.outcome()).isEqualTo(CheckinRecord.Outcome.FAILED);
        assertThat(record.kind()).isEqualTo(ErrorKind.AUTH);
        assertThat(record.roles()).isEmpty();
    }

    @Test
    void noBoundAccountSkipsWithoutTouchingMihoyo() {
        when(credentials.isPresent()).thenReturn(false);

        CheckinRecord record = scheduler.run(CheckinRecord.Trigger.SCHEDULED);

        assertThat(record.outcome()).isEqualTo(CheckinRecord.Outcome.SKIPPED);
        assertThat(record.message()).contains("未绑定");
        assertThat(record.done()).isFalse();
        assertThat(record.attention()).isFalse();
        verifyNoInteractions(checkin);
    }

    @Test
    void scheduledRunSkipsWhenTodayAlreadyDone() {
        saveToday(CheckinRecord.Outcome.DONE);

        CheckinRecord record = scheduler.run(CheckinRecord.Trigger.SCHEDULED);

        assertThat(record.done()).isTrue();
        verify(checkin, never()).checkinAll();
    }

    @Test
    void scheduledRunRetriesWhenTodayWasOnlyPartial() {
        saveToday(CheckinRecord.Outcome.PARTIAL);
        when(checkin.checkinAll()).thenReturn(new CheckinService.Summary(
                List.of(role("ALREADY")), "1 个角色：已签 1"));

        CheckinRecord record = scheduler.run(CheckinRecord.Trigger.SCHEDULED);

        assertThat(record.outcome()).isEqualTo(CheckinRecord.Outcome.DONE);
        verify(checkin).checkinAll();
    }

    @Test
    void manualWithoutForceDoesNotRepeatWhenDone() {
        saveToday(CheckinRecord.Outcome.DONE);

        CheckinRecord record = scheduler.runManual(false);

        assertThat(record.done()).isTrue();
        verify(checkin, never()).checkinAll();
    }

    @Test
    void manualForceRerunsEvenWhenDone() {
        saveToday(CheckinRecord.Outcome.DONE);
        when(checkin.checkinAll()).thenReturn(new CheckinService.Summary(
                List.of(role("ALREADY")), "1 个角色：已签 1"));

        CheckinRecord record = scheduler.runManual(true);

        assertThat(record.trigger()).isEqualTo(CheckinRecord.Trigger.MANUAL);
        assertThat(record.outcome()).isEqualTo(CheckinRecord.Outcome.DONE);
        verify(checkin).checkinAll();
    }

    @Test
    void unexpectedExceptionBecomesFailedRecordInsteadOfCrashingTheJob() {
        when(checkin.checkinAll()).thenThrow(new IllegalStateException("socket closed"));

        CheckinRecord record = scheduler.run(CheckinRecord.Trigger.SCHEDULED);

        assertThat(record.outcome()).isEqualTo(CheckinRecord.Outcome.FAILED);
        assertThat(record.kind()).isEqualTo(ErrorKind.UNKNOWN);
        assertThat(record.message()).contains("socket closed");
    }

    @Test
    void disabledSchedulerSkipsCronButStillAllowsManualRuns() {
        CheckinScheduler disabled = new CheckinScheduler(checkin, store, credentials, "local", false,
                CRON, "Asia/Shanghai", Duration.ofSeconds(0));

        assertThat(disabled.run(CheckinRecord.Trigger.SCHEDULED)).isNull();
        verify(checkin, never()).checkinAll();

        when(checkin.checkinAll()).thenReturn(new CheckinService.Summary(
                List.of(role("SIGNED")), "1 个角色：新签 1"));
        assertThat(disabled.run(CheckinRecord.Trigger.MANUAL).outcome())
                .isEqualTo(CheckinRecord.Outcome.DONE);
        verify(checkin).checkinAll();
    }

    @Test
    void recordFromAnotherUserDoesNotCountAsTodays() {
        store.save(new CheckinRecord("someone-else", today(), CheckinRecord.Trigger.SCHEDULED,
                CheckinRecord.Outcome.DONE, null, today() + "T00:10:00+08:00",
                today() + "T00:10:05+08:00", "别人的记录", List.of()));
        when(checkin.checkinAll()).thenReturn(new CheckinService.Summary(
                List.of(role("SIGNED")), "1 个角色：新签 1"));

        CheckinRecord record = scheduler.run(CheckinRecord.Trigger.SCHEDULED);

        assertThat(record.userId()).isEqualTo("local");
        verify(checkin).checkinAll();
    }

    @Test
    void statusFlagsAMissingRecordOnlyAfterTheScheduledTime() {
        ZonedDateTime afterCron = ZonedDateTime.of(2026, 11, 13, 9, 0, 0, 0, ZONE);
        ZonedDateTime beforeCron = ZonedDateTime.of(2026, 11, 13, 0, 5, 0, 0, ZONE);
        ZonedDateTime oneMinuteAfter = ZonedDateTime.of(2026, 11, 13, 0, 11, 0, 0, ZONE);

        assertThat(scheduler.attentionOf(Optional.empty(), "2026-11-13", afterCron))
                .contains("还没有签到记录");
        assertThat(scheduler.attentionOf(Optional.empty(), "2026-11-13", beforeCron)).isNull();
        assertThat(scheduler.cronPassed("2026-11-13", beforeCron)).isFalse();
        assertThat(scheduler.cronPassed("2026-11-13", oneMinuteAfter)).isTrue();
    }

    @Test
    void statusExplainsAnUnfinishedRecord() {
        CheckinRecord failed = new CheckinRecord("local", "2026-11-13",
                CheckinRecord.Trigger.SCHEDULED, CheckinRecord.Outcome.FAILED, ErrorKind.AUTH,
                "2026-11-13T00:10:00+08:00", "2026-11-13T00:10:01+08:00",
                "读取绑定角色失败：登录状态失效", List.of());

        assertThat(scheduler.attentionOf(Optional.of(failed), "2026-11-13", ZonedDateTime.now(ZONE)))
                .contains("今天签到未完成").contains("登录状态失效");
    }

    @Test
    void nextRunRollsOverToTomorrowAfterTodaysTime() {
        ZonedDateTime afterCron = ZonedDateTime.of(2026, 11, 13, 9, 0, 0, 0, ZONE);
        ZonedDateTime beforeCron = ZonedDateTime.of(2026, 11, 13, 0, 5, 0, 0, ZONE);

        assertThat(scheduler.nextRun(afterCron)).isEqualTo("2026-11-14T00:10+08:00");
        assertThat(scheduler.nextRun(beforeCron)).isEqualTo("2026-11-13T00:10+08:00");
    }

    @Test
    void statusExposesTodayAndHistory() {
        saveToday(CheckinRecord.Outcome.DONE);

        CheckinScheduler.Status status = scheduler.status();

        assertThat(status.enabled()).isTrue();
        assertThat(status.cron()).isEqualTo(CRON);
        assertThat(status.zone()).isEqualTo("Asia/Shanghai");
        assertThat(status.today()).isNotNull();
        assertThat(status.today().done()).isTrue();
        assertThat(status.needsAttention()).isFalse();
        assertThat(status.recent()).hasSize(1);
    }

    private void saveToday(CheckinRecord.Outcome outcome) {
        store.save(new CheckinRecord("local", today(), CheckinRecord.Trigger.SCHEDULED, outcome, null,
                today() + "T00:10:00+08:00", today() + "T00:10:05+08:00", "已跑过", List.of()));
    }

    private static String today() {
        return LocalDate.now(ZONE).toString();
    }

    private static CheckinService.RoleResult role(String status) {
        return new CheckinService.RoleResult("hk4e_cn", "原神", "100848866", "cn_gf01",
                status, "今天已经签过了", 12, "", null);
    }
}
