package com.luobo.hoyoagent.mihoyo.checkin;

import com.luobo.hoyoagent.agent.notify.Notifier;
import com.luobo.hoyoagent.mihoyo.credential.Account;
import com.luobo.hoyoagent.mihoyo.credential.AccountService;
import com.luobo.hoyoagent.mihoyo.credential.Credential;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
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
 *   <li><b>结果顺带推给 {@link Notifier}</b>（默认关，{@code hoyo.notify.enabled=true} 才发）：
 *       推送失败只打日志，不影响签到结果。</li>
 * </ol>
 *
 * <p>多用户：定时任务走 {@link #runAll}，遍历 {@link AccountService#all()}，每人各自判定
 * "今天是不是已经 DONE"；记录按 userId 分目录（见 {@link CheckinRecordStore}）。
 * {@link #run} 保留单账号语义，只服务网页调试口和单元测试。
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

    private final AccountService accounts;

    /** 出站通知：默认关（hoyo.notify.enabled=false），关着时是 NOOP 之外的一个空实现，不影响签到。 */
    private final Notifier notifier;

    private final String userId;

    private final boolean enabled;

    private final String cron;

    private final ZoneId zone;

    private final Duration startupDelay;

    /** 定时与补签不能同时跑：两边都会真的发请求 */
    private final ReentrantLock lock = new ReentrantLock();

    /** 账号之间最多 3 个并发：再多就是在给米游社刷风控。 */
    private static final int MAX_CONCURRENT_ACCOUNTS = 3;

    /**
     * @param cron 注解里的默认值必须和这里一致——{@code @Scheduled} 读不到构造参数
     */
    public CheckinScheduler(CheckinService checkin,
                            CheckinRecordStore records,
                            AccountService accounts,
                            Notifier notifier,
                            @Value("${hoyo.user-id:local}") String userId,
                            @Value("${hoyo.checkin.enabled:true}") boolean enabled,
                            @Value("${hoyo.checkin.cron:0 10 0 * * *}") String cron,
                            @Value("${hoyo.checkin.zone:Asia/Shanghai}") String zone,
                            @Value("${hoyo.checkin.startup-delay:60s}") Duration startupDelay) {
        this.checkin = checkin;
        this.records = records;
        this.accounts = accounts;
        this.notifier = notifier;
        this.userId = userId;
        this.enabled = enabled;
        this.cron = cron;
        this.zone = ZoneId.of(zone);
        this.startupDelay = startupDelay;
    }

    @Scheduled(cron = "${hoyo.checkin.cron:0 10 0 * * *}", zone = "${hoyo.checkin.zone:Asia/Shanghai}")
    public void daily() {
        runAll(CheckinRecord.Trigger.SCHEDULED);
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
                runAll(CheckinRecord.Trigger.STARTUP);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                log.error("{} 启动补签异常: {}", ALERT, e.toString());
            }
        });
    }

    /**
     * 单账号入口：只跑 {@code hoyo.user-id} 那一个（网页调试页和单元测试用）。
     *
     * <p>定时任务走 {@link #runAll}——那里才是多用户。两个方法共用 {@link #execute}，
     * 区别只在"跑谁"，不在"怎么跑"。
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
            Credential credential = accounts.find(userId).orElse(null);
            return execute(userId, credential, date, trigger);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 跑所有已绑定账号：账号间最多 3 并发，账号内串行（由 {@link CheckinService} 保证）。
     *
     * <p>每个账号各自判断"今天是不是已经 DONE"，所以某个人手动补跑过，不会拖累其他人。
     */
    public List<CheckinRecord> runAll(CheckinRecord.Trigger trigger) {
        if (trigger != CheckinRecord.Trigger.MANUAL && !enabled) {
            log.info("[CHECKIN] 定时签到已关闭（hoyo.checkin.enabled=false），跳过 {}", trigger);
            return List.of();
        }
        if (!lock.tryLock()) {
            log.warn("[CHECKIN] 上一次签到还没跑完，跳过本次 {}", trigger);
            return List.of();
        }
        try {
            String date = LocalDate.now(zone).toString();
            List<Account> all = accounts.all();
            if (all.isEmpty()) {
                // 一个号都没绑：留一条记录，状态页才知道"跑过了，只是没东西可签"
                return List.of(finish(userId, date, trigger, CheckinRecord.Outcome.SKIPPED, null,
                        Instant.now(), "未绑定米游社账号：先扫码登录或填 HOYO_COOKIE", List.of()));
            }

            List<Account> pending = new ArrayList<>();
            for (Account account : all) {
                Optional<CheckinRecord> today = records.find(account.userId(), date);
                if (trigger != CheckinRecord.Trigger.MANUAL && today.filter(CheckinRecord::done).isPresent()) {
                    log.info("[CHECKIN] {} 今天已签过，跳过", mask(account.userId()));
                    continue;
                }
                pending.add(account);
            }
            if (pending.isEmpty()) {
                log.info("[CHECKIN] {} 个账号今天都已完成，跳过 {}", all.size(), trigger);
                return List.of();
            }

            log.info("[CHECKIN] {} 开始：{} 个账号待签（共 {} 个）", trigger, pending.size(), all.size());
            return runConcurrently(pending, date, trigger);
        } finally {
            lock.unlock();
        }
    }

    /** 一个账号一个虚拟线程；信号量兜住并发上限。 */
    private List<CheckinRecord> runConcurrently(List<Account> pending, String date,
                                                CheckinRecord.Trigger trigger) {
        List<CheckinRecord> results = Collections.synchronizedList(new ArrayList<>());
        Semaphore permits = new Semaphore(MAX_CONCURRENT_ACCOUNTS);
        List<Thread> threads = new ArrayList<>();

        for (Account account : pending) {
            threads.add(Thread.ofVirtual().name("checkin-" + mask(account.userId())).start(() -> {
                try {
                    permits.acquire();
                    try {
                        // 抖动：几个号同一毫秒打过去，看起来就像脚本
                        sleep(ThreadLocalRandom.current().nextInt(0, 400));
                        results.add(execute(account.userId(), account.credential(), date, trigger));
                    } finally {
                        permits.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
                    log.error("{} {} 签到异常: user={} err={}",
                            ALERT, trigger, mask(account.userId()), e.toString());
                }
            }));
        }
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return List.copyOf(results);
    }

    private CheckinRecord execute(String userId, Credential credential, String date,
                                  CheckinRecord.Trigger trigger) {
        Instant started = Instant.now();

        if (credential == null || credential.cookieHeader() == null || credential.cookieHeader().isBlank()) {
            return finish(userId, date, trigger, CheckinRecord.Outcome.SKIPPED, null, started,
                    "未绑定米游社账号：先扫码登录或填 HOYO_COOKIE", List.of());
        }

        CheckinService.Summary summary;
        try {
            summary = checkin.checkinAll(credential);
        } catch (RuntimeException e) {
            log.error("{} {} 签到抛出异常: {}", ALERT, trigger, e.toString());
            return finish(userId, date, trigger, CheckinRecord.Outcome.FAILED, ErrorKind.UNKNOWN, started,
                    "签到异常：" + e.getMessage(), List.of());
        }

        CheckinRecord.Outcome outcome = classify(summary.results());
        ErrorKind kind = summary.kind() == null ? worstKind(summary.results()) : summary.kind();
        return finish(userId, date, trigger, outcome, kind, started, summary.message(), summary.results());
    }

    private CheckinRecord finish(String userId,
                                 String date,
                                 CheckinRecord.Trigger trigger,
                                 CheckinRecord.Outcome outcome,
                                 ErrorKind kind,
                                 Instant started,
                                 String message,
                                 List<CheckinService.RoleResult> roles) {
        CheckinRecord record = new CheckinRecord(
                userId, date, trigger, outcome, kind,
                started.atZone(zone).toOffsetDateTime().toString(),
                Instant.now().atZone(zone).toOffsetDateTime().toString(),
                message, roles);
        records.save(record);

        if (record.attention()) {
            log.error("{} {} {} 未完成（{}）：{}（kind={}）", ALERT, mask(userId), trigger, outcome, message, kind);
        } else if (outcome == CheckinRecord.Outcome.SKIPPED) {
            log.warn("[CHECKIN] {} 跳过签到：{}", mask(userId), message);
        } else {
            log.info("[CHECKIN] {} 完成 {}：{}", mask(userId), trigger, message);
        }

        notifyQuietly(record);
        return record;
    }

    /**
     * 通知是旁路：发送失败不能影响签到结果，更不能让已经落盘的记录被当成"没跑过"。
     * 接口契约上 {@link Notifier} 自己吞异常，但这里再包一层——契约不该依赖实现。
     */
    private void notifyQuietly(CheckinRecord record) {
        try {
            notifier.send(CheckinMessages.of(record));
        } catch (RuntimeException e) {
            log.warn("[CHECKIN] 通知发送异常（不影响签到）：{}", e.toString());
        }
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
                records.recent(userId, 7));
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

    /** 记录按用户分目录后，这里只查自己的那份；旧文件的归属由 store 判定。 */
    private Optional<CheckinRecord> todayRecord(String date) {
        return records.find(userId, date);
    }


    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String mask(String userId) {
        if (userId == null || userId.isBlank()) {
            return "***";
        }
        return userId.length() <= 8 ? "***" : userId.substring(0, 4) + "***" + userId.substring(userId.length() - 4);
    }


    /**
     * 手动补跑：跑所有账号。返回本地那条是为了兼容 {@code POST /api/checkin/run} 的响应，
     * 全量结果留给 M6 的管理后台按用户展示。
     */
    public CheckinRecord runManual(boolean force) {
        String date = LocalDate.now(zone).toString();
        if (!force) {
            Optional<CheckinRecord> today = todayRecord(date);
            if (today.filter(CheckinRecord::done).isPresent()) {
                log.info("[CHECKIN] 今天已经签过，未重复请求（force=true 可强制）");
                return today.get();
            }
        }
        List<CheckinRecord> results = runAll(CheckinRecord.Trigger.MANUAL);
        return results.stream().filter(r -> userId.equals(r.userId())).findFirst()
                .orElse(results.isEmpty() ? null : results.get(0));
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
