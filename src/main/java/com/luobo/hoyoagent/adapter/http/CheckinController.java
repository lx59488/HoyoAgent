package com.luobo.hoyoagent.adapter.http;

import com.luobo.hoyoagent.common.exception.ErrorCode;
import com.luobo.hoyoagent.common.result.ApiResult;
import com.luobo.hoyoagent.mihoyo.checkin.CheckinRecord;
import com.luobo.hoyoagent.mihoyo.checkin.CheckinRunResult;
import com.luobo.hoyoagent.mihoyo.checkin.CheckinScheduler;
import com.luobo.hoyoagent.mihoyo.checkin.CheckinService;
import com.luobo.hoyoagent.mihoyo.credential.AccountService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 签到入口，三个各司其职：
 *
 * <pre>
 * POST /api/checkin        ← 裸调 CheckinService：不读/不写记录，用来验证米游社客户端本身
 * POST /api/checkin/run    ← 走调度那条路（含"今天已签就跳过"、写记录），?force=true 强制重跑
 * GET  /api/checkin/status ← 今天的结果 + 最近 7 天 + 该不该看一眼
 * </pre>
 *
 * <p>写操作一律 POST：它们真的会改米游社那边的状态。
 *
 * <p>注意 {@code POST /api/checkin} 只签 <b>local</b> 那一个账号（调试用）；多账号请走
 * {@code /run}（它遍历 {@link AccountService#all()}）。原先这个语义藏在 {@code CheckinService}
 * 的无参重载里，删掉之后必须由这里显式说出来。
 */
@RestController
@RequestMapping("/api/checkin")
public class CheckinController {

    private final CheckinService checkinService;

    private final CheckinScheduler scheduler;

    private final AccountService accounts;

    public CheckinController(CheckinService checkinService, CheckinScheduler scheduler, AccountService accounts) {
        this.checkinService = checkinService;
        this.scheduler = scheduler;
        this.accounts = accounts;
    }

    /** 裸调：只签 local 账号、只回本次结果，不落盘。 */
    @PostMapping
    public ApiResult<CheckinRunResult> checkin() {
        String userId = accounts.localUserId();
        return ApiResult.success(checkinService.checkinAll(accounts.requireCredential(userId)));
    }

    /** 调度路径：今天已经 DONE 且未 force 时不会重复请求米游社。 */
    @PostMapping("/run")
    public ApiResult<CheckinRecord> run(@RequestParam(value = "force", defaultValue = "false") boolean force) {
        CheckinRecord record = scheduler.runManual(force);
        if (record == null) {
            return ApiResult.error(ErrorCode.BAD_REQUEST, "签到正在进行中，稍后再试");
        }
        return ApiResult.success(record);
    }

    @GetMapping("/status")
    public ApiResult<CheckinScheduler.Status> status() {
        return ApiResult.success(scheduler.status());
    }
}
