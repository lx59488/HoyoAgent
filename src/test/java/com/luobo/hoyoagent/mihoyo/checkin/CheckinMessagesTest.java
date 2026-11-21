package com.luobo.hoyoagent.mihoyo.checkin;

import com.luobo.hoyoagent.channel.notify.Notification;
import com.luobo.hoyoagent.mihoyo.error.ErrorKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 签到结果 → 通知文案的映射；改了哪个分支，这里必须跟着响。 */
class CheckinMessagesTest {

    @Test
    void levelFollowsOutcome() {
        assertThat(CheckinMessages.level(record(CheckinRecord.Outcome.DONE, null)))
                .isEqualTo(Notification.Level.INFO);
        assertThat(CheckinMessages.level(record(CheckinRecord.Outcome.SKIPPED, null)))
                .isEqualTo(Notification.Level.INFO);
        assertThat(CheckinMessages.level(record(CheckinRecord.Outcome.PARTIAL, ErrorKind.AUTH)))
                .isEqualTo(Notification.Level.WARN);
        assertThat(CheckinMessages.level(record(CheckinRecord.Outcome.FAILED, ErrorKind.AUTH)))
                .isEqualTo(Notification.Level.ERROR);
    }

    @Test
    void titleCarriesTheOutcomeAndDate() {
        assertThat(CheckinMessages.of(record(CheckinRecord.Outcome.DONE, null)).title())
                .contains("完成").contains("2026-11-18");
        assertThat(CheckinMessages.of(record(CheckinRecord.Outcome.FAILED, ErrorKind.AUTH)).title())
                .contains("失败").contains("2026-11-18");
    }

    @Test
    void textIncludesTheMessageAndErrorKind() {
        Notification notification = CheckinMessages.of(record(CheckinRecord.Outcome.FAILED, ErrorKind.AUTH));

        assertThat(notification.text()).contains("读取绑定角色失败").contains("AUTH");
    }

    private static CheckinRecord record(CheckinRecord.Outcome outcome, ErrorKind kind) {
        return new CheckinRecord("local", "2026-11-18", CheckinRecord.Trigger.SCHEDULED, outcome, kind,
                "2026-11-18T00:10:00+08:00", "2026-11-18T00:10:05+08:00",
                "读取绑定角色失败", List.of());
    }
}
