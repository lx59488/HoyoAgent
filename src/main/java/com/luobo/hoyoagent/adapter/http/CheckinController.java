package com.luobo.hoyoagent.adapter.http;

import com.luobo.hoyoagent.common.result.Result;
import com.luobo.hoyoagent.mihoyo.checkin.CheckinService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 手动触发签到（Agent 之外的第二条入口，方便 curl 验证）。
 *
 * <p>用 POST 而不是 GET：它会改状态（真的签），不该被浏览器预取或缓存。
 */
@RestController
public class CheckinController {

    private final CheckinService checkinService;

    public CheckinController(CheckinService checkinService) {
        this.checkinService = checkinService;
    }

    @PostMapping("/api/checkin")
    public Result<CheckinService.Summary> checkin() {
        return Result.success(checkinService.checkinAll());
    }
}
