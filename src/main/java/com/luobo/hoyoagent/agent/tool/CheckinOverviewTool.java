package com.luobo.hoyoagent.agent.tool;

import com.luobo.hoyoagent.agent.observability.ToolCallRecorder;
import com.luobo.hoyoagent.common.exception.BusinessException;
import com.luobo.hoyoagent.mihoyo.checkin.CheckinOverview;
import com.luobo.hoyoagent.mihoyo.checkin.CheckinOverviewService;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class CheckinOverviewTool {

    private static final Logger log = LoggerFactory.getLogger(CheckinOverviewTool.class);

    private final ToolCallRecorder recorder;

    private final CheckinOverviewService service;

    public CheckinOverviewTool(ToolCallRecorder recorder, CheckinOverviewService service) {
        this.recorder = recorder;
        this.service = service;
    }

    @Tool("""
            查看当前账号的签到总览：今天原神/星穹铁道/绝区零各签没签、累计签到天数、
            本月大约漏签几天、最近 7 天的签到结果。纯查询，不会执行签到。
            用户问「这个月漏了几天」「今天签到什么情况」「最近签到正常吗」时调用。
            用户要求「帮我签到」「帮我领原石」时不要用本工具，改用 checkinToday。
            """)
    public String getCheckinOverview() {
        log.info("[TOOL] getCheckinOverview");
        recorder.record("getCheckinOverview", "");
        try {
            return format(service.overview());
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
