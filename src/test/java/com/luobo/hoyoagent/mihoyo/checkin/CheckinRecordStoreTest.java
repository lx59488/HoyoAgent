package com.luobo.hoyoagent.mihoyo.checkin;

import com.luobo.hoyoagent.mihoyo.error.ErrorKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 记录文件的两条硬约束在这里锁住：读坏了当没记录，写同一日期是覆盖而不是追加。
 */
class CheckinRecordStoreTest {

    @TempDir
    Path dir;

    private CheckinRecordStore store;

    @BeforeEach
    void setUp() {
        // 不能用字段初始化：@TempDir 在实例构造之后才注入
        store = new CheckinRecordStore(dir, JsonMapper.builder().build());
    }

    @Test
    void saveThenFindRoundTripsEveryField() {
        List<CheckinService.RoleResult> roles = List.of(
                new CheckinService.RoleResult("hk4e_cn", "原神", "100848866", "cn_gf01",
                        "SIGNED", "签到成功", 12, "「原石」x60", null),
                new CheckinService.RoleResult("hkrpg_cn", "崩坏：星穹铁道", "103871907", "prod_gf_cn",
                        "FAILED", "登录状态失效，请重新扫码绑定", 7, "", ErrorKind.AUTH));
        CheckinRecord record = new CheckinRecord(
                "local", "2026-11-13", CheckinRecord.Trigger.SCHEDULED, CheckinRecord.Outcome.PARTIAL,
                ErrorKind.AUTH,
                "2026-11-13T00:10:00.123+08:00", "2026-11-13T00:10:04.456+08:00",
                "2 个角色：新签 1，失败/需处理 1", roles);

        store.save(record);

        CheckinRecord loaded = store.find("2026-11-13").orElseThrow();
        assertThat(loaded.userId()).isEqualTo("local");
        assertThat(loaded.outcome()).isEqualTo(CheckinRecord.Outcome.PARTIAL);
        assertThat(loaded.trigger()).isEqualTo(CheckinRecord.Trigger.SCHEDULED);
        assertThat(loaded.kind()).isEqualTo(ErrorKind.AUTH);
        assertThat(loaded.message()).isEqualTo("2 个角色：新签 1，失败/需处理 1");
        assertThat(loaded.roles()).hasSize(2);
        assertThat(loaded.roles().get(1).kind()).isEqualTo(ErrorKind.AUTH);
        // record 的值语义：整条读回来应该和写进去的相等
        assertThat(loaded).isEqualTo(record);
    }

    @Test
    void savingSameDateOverwritesWithoutLeavingTempFiles() throws Exception {
        store.save(record("2026-11-13", CheckinRecord.Outcome.DONE));
        store.save(record("2026-11-13", CheckinRecord.Outcome.FAILED));

        assertThat(store.find("2026-11-13").orElseThrow().outcome())
                .isEqualTo(CheckinRecord.Outcome.FAILED);
        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files.count()).isEqualTo(1);
        }
    }

    @Test
    void corruptFileIsTreatedAsMissingInsteadOfThrowing() throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("2026-11-13.json"), "{ 这不是 JSON", StandardCharsets.UTF_8);

        assertThat(store.find("2026-11-13")).isEmpty();
    }

    @Test
    void missingDirectoryIsEmptyNotAnError() {
        assertThat(store.find("2026-11-13")).isEmpty();
        assertThat(store.recent(7)).isEmpty();
    }

    @Test
    void recentReturnsNewestFirstAndSkipsGaps() {
        store.save(record("2026-11-09", CheckinRecord.Outcome.DONE));
        store.save(record("2026-11-12", CheckinRecord.Outcome.FAILED));
        store.save(record("2026-11-10", CheckinRecord.Outcome.DONE));

        assertThat(store.recent(2)).extracting(CheckinRecord::date)
                .containsExactly("2026-11-12", "2026-11-10");
        assertThat(store.recent(10)).hasSize(3);
    }

    @Test
    void nullRolesAreNormalisedSoCallersNeverSeeNull() {
        CheckinRecord record = new CheckinRecord("local", "2026-11-13",
                CheckinRecord.Trigger.MANUAL, CheckinRecord.Outcome.SKIPPED, null,
                "2026-11-13T12:00:00+08:00", "2026-11-13T12:00:00+08:00", "未绑定", null);

        assertThat(record.roles()).isEmpty();
    }

    private static CheckinRecord record(String date, CheckinRecord.Outcome outcome) {
        return new CheckinRecord("local", date, CheckinRecord.Trigger.SCHEDULED, outcome, null,
                date + "T00:10:00+08:00", date + "T00:10:05+08:00", "3 个角色", List.of());
    }
}
