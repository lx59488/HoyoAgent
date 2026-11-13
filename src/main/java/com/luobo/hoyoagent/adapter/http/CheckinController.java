package com.luobo.hoyoagent.adapter.http;

import com.luobo.hoyoagent.common.exception.ErrorCode;
import com.luobo.hoyoagent.common.result.Result;
import com.luobo.hoyoagent.mihoyo.checkin.CheckinRecord;
import com.luobo.hoyoagent.mihoyo.checkin.CheckinScheduler;
import com.luobo.hoyoagent.mihoyo.checkin.CheckinService;
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
 */
@RestController
@RequestMapping("/api/checkin")
public class CheckinController {

    private final CheckinService checkinService;

    private final CheckinScheduler scheduler;

    public CheckinController(CheckinService checkinService, CheckinScheduler scheduler) {
        this.checkinService = checkinService;
        this.scheduler = scheduler;
    }

    /** 裸调：只回本次结果，不落盘。 */
    @PostMapping
    public Result<CheckinService.Summary> checkin() {
        return Result.success(checkinService.checkinAll());
    }

    /** 调度路径：今天已经 DONE 且未 force 时不会重复请求米游社。 */
    @PostMapping("/run")
    public Result<CheckinRecord> run(@RequestParam(value = "force", defaultValue = "false") boolean force) {
        CheckinRecord record = scheduler.runManual(force);
        if (record == null) {
            return Result.error(ErrorCode.BAD_REQUEST, "签到正在进行中，稍后再试");
        }
        return Result.success(record);
    }

    @GetMapping("/status")
    public Result<CheckinScheduler.Status> status() {
        return Result.success(scheduler.status());
    }
}
