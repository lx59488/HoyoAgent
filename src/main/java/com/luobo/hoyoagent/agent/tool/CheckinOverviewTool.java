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
    static final String NO_IDENTITY = "没有绑定米游社账号，查不到签到总览。请绑定后重试";

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
        // 先记再判身份：被拒的调用也是一次调用（模型选中了它），不记就看不见——评测基线靠这一行才有数据
        recorder.record("getCheckinOverview", "");
        String userId = sessions.userIdOf(memoryId).orElse(null);
        if (userId == null) {
            log.info("[TOOL] getCheckinOverview 无身份会话，拒绝");
            return NO_IDENTITY;
        }
        log.info("[TOOL] getCheckinOverview user={}", Mask.id(userId));
        try {
            // 用 find 而不是 require：未绑定时要回"友好总览"，不是抛异常
            return format(service.overview(userId, accounts.credentialOf(userId).orElse(null)));
        } catch (BusinessException e) {
            return "查询签到总览失败：" + e.getMessage();
        }
    }

    /**
     * 工具输出契约（C5）：<b>只给事实，不给措辞</b>。
     *
     * <p>和 {@link CheckinTool#format} 同一套规矩，另加两条本工具特有的：
     * <ul>
     *   <li>{@code outcome} / {@code trigger} 必须翻成人话——{@code DONE（SCHEDULED）} 是给日志和落盘看的，
     *       模型看见就会原样说给用户；</li>
     *   <li>推算出来的值要标「（推算）」——漏签天数是「今天几号 − 累计天数」得到的，不是服务端给的数字，
     *       模型得知道这一点才有可能在措辞上保留「约」。</li>
     * </ul>
     */
    static String format(CheckinOverview overview) {
        StringBuilder out = new StringBuilder("今日 ").append(overview.today());
        if (overview.games().isEmpty()) {
            out.append("\n- 没有可查询的角色");
            if (!overview.message().isBlank()) {
                out.append("：").append(overview.message());
            }
            return out.toString();
        }
        for (CheckinOverview.Game game : overview.games()) {
            out.append("\n- ").append(game.gameName()).append(' ').append(game.uid())
                    .append("：").append(describe(game));
        }
        out.append("\n最近");
        if (overview.recent().isEmpty()) {
            out.append("\n- 还没有签到记录（定时任务可能没跑过）");
        } else {
            for (CheckinOverview.Recent recent : overview.recent()) {
                out.append("\n- ").append(recent.date()).append(' ').append(trigger(recent.trigger()))
                        .append("：").append(outcome(recent.outcome()))
                        .append("（").append(recent.message()).append("）");
            }
        }
        return out.toString();
    }

    private static String describe(CheckinOverview.Game game) {
        StringBuilder out = new StringBuilder(switch (game.status()) {
            case "SIGNED" -> "今天已签";
            case "UNSIGNED" -> "今天还没签";
            case "FIRST_BIND" -> "还没绑定该游戏角色";
            default -> "查询失败";
        });
        if (game.totalDays() > 0) {
            out.append(" · 累计 ").append(game.totalDays()).append(" 天")
                    .append(" · 本月约漏 ").append(game.missedEstimate()).append(" 天（推算）");
        }
        return out.toString();
    }

    private static String outcome(String value) {
        return switch (value) {
            case "DONE" -> "全部成功";
            case "PARTIAL" -> "部分成功";
            case "FAILED" -> "失败";
            case "SKIPPED" -> "未执行";
            default -> value;
        };
    }

    private static String trigger(String value) {
        return switch (value) {
            case "SCHEDULED" -> "定时任务";
            case "STARTUP" -> "启动补签";
            case "MANUAL" -> "手动";
            default -> value;
        };
    }
}