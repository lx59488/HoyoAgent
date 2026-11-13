package com.luobo.hoyoagent.mihoyo.checkin;

import com.luobo.hoyoagent.mihoyo.credential.CredentialProvider;
import com.luobo.hoyoagent.mihoyo.error.ErrorKind;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 每日自动签到：00:10 跑一次，启动后补一次，结果落盘并可查。
 *
 * <p>三个刻意的决定：
 * <ol>
 *   <li><b>每轮只试一次，失败不自动重试。</b>重试会把"连续 3 天成功"这个验收条件糊掉——
 *       失败就该亮灯让人看见，而不是靠重试掩盖。手动补跑用 {@code POST /api/checkin/run?force=true}。</li>
 *   <li><b>启动补签</b>：cron 只在进程活着的时候才会触发，笔记本合盖一晚就错过整天。
 *       启动后 {@code hoyo.checkin.startup-delay}（默认 60s）补一次，今天已经有 DONE 记录就跳过。</li>
 *   <li><b>所有失败都走同一个前缀</b> {@link #ALERT}，日志里搜一次就能捞出来；
 *       同时写记录文件，重启后 {@code /api/checkin/status} 还能看见。</li>
 * </ol>
 *
 * <p>单用户假设：记录按日期一个文件，和 {@link CredentialProvider} 只认一个账号一致。
 * 多用户时按 userId 分目录（或上库），{@link CheckinRecord#userId()} 已经先写进文件了。
 */
@Component
public class CheckinScheduler {

    /** 失败统一前缀：{@code Select-String -Pattern '\[CHECKIN\]\[ALERT\]'} */
    public static final String ALERT = "[CHECKIN][ALERT]";

    private static final Logger log = LoggerFactory.getLogger(CheckinScheduler.class);

    /** 多个失败同时出现时，先处理哪个类别。越靠前越"挡路"。 */
    private static final List<ErrorKind> KIND_PRIORITY = List.of(
            ErrorKind.AUTH,
            ErrorKind.NEED_VERIFY,
            ErrorKind.SIGN,
            ErrorKind.RISK,
            ErrorKind.RATE_LIMIT,
            ErrorKind.DATA_PRIVATE,
            ErrorKind.UNKNOWN);

    private final CheckinService checkin;

    private final CheckinRecordStore records;

    private final CredentialProvider credentials;

    private final String userId;

    private final boolean enabled;

    private final String cron;

    private final ZoneId zone;

    private final Duration startupDelay;

    /** 定时与补签不能同时跑：两边都会真的发请求 */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * @param cron 注解里的默认值必须和这里一致——{@code @Scheduled} 读不到构造参数
     */
    public CheckinScheduler(CheckinService checkin,
                            CheckinRecordStore records,
                            CredentialProvider credentials,
                            @Value("${hoyo.user-id:local}") String userId,
                            @Value("${hoyo.checkin.enabled:true}") boolean enabled,
                            @Value("${hoyo.checkin.cron:0 10 0 * * *}") String cron,
                            @Value("${hoyo.checkin.zone:Asia/Shanghai}") String zone,
                            @Value("${hoyo.checkin.startup-delay:60s}") Duration startupDelay) {
        this.checkin = checkin;
        this.records = records;
        this.credentials = credentials;
        this.userId = userId;
        this.enabled = enabled;
        this.cron = cron;
        this.zone = ZoneId.of(zone);
        this.startupDelay = startupDelay;
    }

    @Scheduled(cron = "${hoyo.checkin.cron:0 10 0 * * *}", zone = "${hoyo.checkin.zone:Asia/Shanghai}")
    public void daily() {
        run(CheckinRecord.Trigger.SCHEDULED);
    }

    /**
     * 启动补签。放在虚拟线程里：线程是守护线程，测试 JVM 不会被它拖住，
     * 也不会把 {@code SpringApplication.run} 卡住（补签最长几秒）。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void catchUpAfterStartup() {
        if (!enabled) {
            return;
        }
        long delay = Math.max(0, startupDelay.toMillis());
        Thread.ofVirtual().name("checkin-catchup").start(() -> {
            try {
                if (delay > 0) {
                    Thread.sleep(delay);
                }
                run(CheckinRecord.Trigger.STARTUP);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                log.error("{} 启动补签异常: {}", ALERT, e.toString());
            }
        });
    }

    /** 人主动触发：今天已经 DONE 就不重复请求（{@code force} 可强制重跑）。 */
    public CheckinRecord runManual(boolean force) {
        if (!force) {
            Optional<CheckinRecord> today = todayRecord(LocalDate.now(zone).toString());
            if (today.filter(CheckinRecord::done).isPresent()) {
                log.info("[CHECKIN] 今天已经签过，未重复请求（force=true 可强制）");
                return today.get();
            }
        }
        return run(CheckinRecord.Trigger.MANUAL);
    }

    /**
     * @return 本次运行的记录；功能关闭或上一次还没跑完时返回 {@code null}
     */
    public CheckinRecord run(CheckinRecord.Trigger trigger) {
        if (trigger != CheckinRecord.Trigger.MANUAL && !enabled) {
            log.info("[CHECKIN] 定时签到已关闭（hoyo.checkin.enabled=false），跳过 {}", trigger);
            return null;
        }
        if (!lock.tryLock()) {
            log.warn("[CHECKIN] 上一次签到还没跑完，跳过本次 {}", trigger);
            return null;
        }
        try {
            String date = LocalDate.now(zone).toString();
            Optional<CheckinRecord> today = todayRecord(date);
            if (trigger != CheckinRecord.Trigger.MANUAL && today.filter(CheckinRecord::done).isPresent()) {
                log.info("[CHECKIN] 今天已签过（{}），跳过 {}", CheckinRecordStore.describe(today.get()), trigger);
                return today.get();
            }
            return execute(date, trigger);
        } finally {
            lock.unlock();
        }
    }

    private CheckinRecord execute(String date, CheckinRecord.Trigger trigger) {
        Instant started = Instant.now();

        if (!credentials.isPresent()) {
            return finish(date, trigger, CheckinRecord.Outcome.SKIPPED, null, started,
                    "未绑定米游社账号：先扫码登录或填 HOYO_COOKIE", List.of());
        }

        CheckinService.Summary summary;
        try {
            summary = checkin.checkinAll();
        } catch (RuntimeException e) {
            log.error("{} {} 签到抛出异常: {}", ALERT, trigger, e.toString());
            return finish(date, trigger, CheckinRecord.Outcome.FAILED, ErrorKind.UNKNOWN, started,
                    "签到异常：" + e.getMessage(), List.of());
        }

        CheckinRecord.Outcome outcome = classify(summary.results());
        ErrorKind kind = summary.kind() == null ? worstKind(summary.results()) : summary.kind();
        return finish(date, trigger, outcome, kind, started, summary.message(), summary.results());
    }

    private CheckinRecord finish(String date,
                                 CheckinRecord.Trigger trigger,
                                 CheckinRecord.Outcome outcome,
                                 ErrorKind kind,
                                 Instant started,
                                 String message,
                                 List<CheckinService.RoleResult> roles) {
        CheckinRecord record = new CheckinRecord(
                userId,
                date,
                trigger,
                outcome,
                kind,
                started.atZone(zone).toOffsetDateTime().toString(),
                Instant.now().atZone(zone).toOffsetDateTime().toString(),
                message,
                roles);
        records.save(record);

        if (record.attention()) {
            log.error("{} {} 未完成（{}）：{}（kind={}）", ALERT, trigger, outcome, message, kind);
        } else if (outcome == CheckinRecord.Outcome.SKIPPED) {
            log.warn("[CHECKIN] 跳过签到：{}", message);
        } else {
            log.info("[CHECKIN] 完成 {}：{}", trigger, message);
        }
        return record;
    }

    /** 全部角色都新签/本来签过才算 DONE；空结果（读不到角色）算失败。 */
    static CheckinRecord.Outcome classify(List<CheckinService.RoleResult> results) {
        if (results == null || results.isEmpty()) {
            return CheckinRecord.Outcome.FAILED;
        }
        long ok = results.stream().filter(CheckinService.RoleResult::ok).count();
        if (ok == results.size()) {
            return CheckinRecord.Outcome.DONE;
        }
        return ok == 0 ? CheckinRecord.Outcome.FAILED : CheckinRecord.Outcome.PARTIAL;
    }

    static ErrorKind worstKind(List<CheckinService.RoleResult> results) {
        if (results == null || results.isEmpty()) {
            return null;
        }
        return KIND_PRIORITY.stream()
                .filter(kind -> results.stream().anyMatch(r -> kind == r.kind()))
                .findFirst()
                .orElse(null);
    }

    /** 状态页：今天的结果、最近 7 天、以及"有没有该看一眼的事"。 */
    public Status status() {
        ZonedDateTime now = ZonedDateTime.now(zone);
        String date = now.toLocalDate().toString();
        Optional<CheckinRecord> today = todayRecord(date);
        String attention = attentionOf(today, date, now);
        return new Status(
                enabled,
                cron,
                zone.getId(),
                nextRun(now),
                date,
                today.orElse(null),
                attention != null,
                attention,
                records.recent(7));
    }

    /** @param now 由调用方给，测试才能锁住"还没到点"和"已经过点"两支 */
    String attentionOf(Optional<CheckinRecord> today, String date, ZonedDateTime now) {
        if (today.isPresent()) {
            CheckinRecord record = today.get();
            if (record.done()) {
                return null;
            }
            return "今天签到未完成：" + record.message();
        }
        if (!enabled || !cronPassed(date, now)) {
            return null;
        }
        return "今天还没有签到记录：定时任务可能没跑（应用当时不在线？）";
    }

    /** 今天的 cron 时点是不是已经过去了——过了还没有记录才是异常。 */
    boolean cronPassed(String date, ZonedDateTime now) {
        ZonedDateTime startOfDay = LocalDate.parse(date).atStartOfDay(zone).minusSeconds(1);
        ZonedDateTime scheduled = CronExpression.parse(cron).next(startOfDay);
        return scheduled != null && !scheduled.isAfter(now);
    }

    public String nextRun() {
        return nextRun(ZonedDateTime.now(zone));
    }

    String nextRun(ZonedDateTime now) {
        ZonedDateTime next = CronExpression.parse(cron).next(now);
        return next == null ? null : next.toOffsetDateTime().toString();
    }

    public boolean enabled() {
        return enabled;
    }

    public String cron() {
        return cron;
    }

    public ZoneId zone() {
        return zone;
    }

    /** 别把别人的记录当成自己的：文件名只有日期，userId 在文件里。 */
    private Optional<CheckinRecord> todayRecord(String date) {
        Optional<CheckinRecord> found = records.find(date);
        if (found.isPresent() && !userId.equals(found.get().userId())) {
            log.warn("{} 的签到记录属于另一个 userId（{}），忽略", date, found.get().userId());
            return Optional.empty();
        }
        return found;
    }

    /** 启动时把"到底会不会跑、下次几点"打进日志：比事后猜 cron 写没写对靠谱。 */
    @PostConstruct
    void announce() {
        if (!enabled) {
            log.info("[CHECKIN] 定时签到已关闭（hoyo.checkin.enabled=false），仍可 POST /api/checkin/run 手动跑");
            return;
        }
        try {
            log.info("[CHECKIN] 定时签到已启用 cron=\"{}\" zone={} 下次={} 记录目录={}",
                    cron, zone, nextRun(), records.root());
        } catch (RuntimeException e) {
            throw new IllegalStateException("hoyo.checkin.cron 解析失败: " + cron, e);
        }
    }

    public record Status(boolean enabled,
                         String cron,
                         String zone,
                         String nextRun,
                         String todayDate,
                         CheckinRecord today,
                         boolean needsAttention,
                         String attention,
                         List<CheckinRecord> recent) {
    }
}
