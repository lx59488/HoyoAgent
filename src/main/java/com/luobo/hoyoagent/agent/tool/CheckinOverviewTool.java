package com.luobo.hoyoagent.agent.tool;

import com.luobo.hoyoagent.common.log.Mask;

import com.luobo.hoyoagent.agent.observability.ToolCallRecorder;
import com.luobo.hoyoagent.channel.SessionRegistry;
import com.luobo.hoyoagent.common.exception.BusinessException;
import com.luobo.hoyoagent.mihoyo.checkin.CheckinOverview;
import com.luobo.hoyoagent.mihoyo.checkin.CheckinOverviewService;
import com.luobo.hoyoagent.mihoyo.credential.AccountService;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class CheckinOverviewTool {

    private static final Logger log = LoggerFactory.getLogger(CheckinOverviewTool.class);

    private final ToolCallRecorder recorder;

    private final CheckinOverviewService service;
    private final SessionRegistry sessions;

    private final AccountService accounts;

    /** 没有平台身份时的统一回复。 */
    static final String NO_IDENTITY =
            "没有绑定米游社账号，无法签到。请绑定后重试";

    public CheckinOverviewTool(ToolCallRecorder recorder,
                               CheckinOverviewService service,
                               SessionRegistry sessions,
                               AccountService accounts) {
        this.recorder = recorder;
        this.service = service;
        this.sessions = sessions;
        this.accounts = accounts;
    }

    @Tool("""
            查看当前账号的签到总览：今天原神/星穹铁道/绝区零各签没签、累计签到天数、
            本月大约漏签几天、最近 7 天的签到结果。纯查询，不会执行签到。
            用户问「这个月漏了几天」「今天签到什么情况」「最近签到正常吗」时调用。
            用户要求「帮我签到」「帮我领原石」时不要用本工具，改用 checkinToday。
            """)
    public String getCheckinOverview(@ToolMemoryId String memoryId) {
        String userId = sessions.userIdOf(memoryId).orElse(null);
        if (userId == null) {
            log.info("[TOOL] getCheckinOverview 无身份会话，拒绝");
            return NO_IDENTITY;
        }
        log.info("[TOOL] getCheckinOverview user={}", Mask.id(userId));
        recorder.record("getCheckinOverview", "");
        try {
            // 用 find 而不是 require：未绑定时要回"友好总览"，不是抛异常
            return format(service.overview(userId, accounts.credentialOf(userId).orElse(null)));
        } catch (BusinessException e) {
            return "【结论】查询签到总览失败：" + e.getMessage();
        }
    }

    static String format(CheckinOverview overview) {
        StringBuilder sb = new StringBuilder("【结论】").append(overview.message());
        if (overview.games().isEmpty()) {
            return sb.toString();
        }
        sb.append("\n【今日】").append(overview.today());
        for (CheckinOverview.Game game : overview.games()) {
            sb.append("\n  ").append(game.gameName())
                    .append("（").append(game.uid()).append("）：")
                    .append(describe(game));
        }
        sb.append("\n【最近】");
        if (overview.recent().isEmpty()) {
            sb.append("还没有签到记录（定时任务可能没跑过）");
        } else {
            for (CheckinOverview.Recent recent : overview.recent()) {
                sb.append("\n  ").append(recent.date()).append(" ")
                        .append(recent.outcome()).append("（").append(recent.trigger()).append("）：")
                        .append(recent.message());
            }
        }
        return sb.toString();
    }

    private static String describe(CheckinOverview.Game game) {
        return switch (game.status()) {
            case "SIGNED" -> "今天已签 · 累计 " + game.totalDays()
                    + " 天 · 本月约漏 " + game.missedEstimate() + " 天";
            case "UNSIGNED" -> "今天还没签 · 累计 " + game.totalDays()
                    + " 天 · 本月约漏 " + game.missedEstimate() + " 天";
            default -> game.message();
        };
    }
}
