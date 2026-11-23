package com.luobo.hoyoagent.agent.tool;

import com.luobo.hoyoagent.common.log.Mask;

import com.luobo.hoyoagent.agent.observability.ToolCallRecorder;
import com.luobo.hoyoagent.channel.SessionRegistry;
import com.luobo.hoyoagent.common.exception.BusinessException;
import com.luobo.hoyoagent.mihoyo.checkin.CheckinRoleResult;
import com.luobo.hoyoagent.mihoyo.checkin.CheckinRunResult;
import com.luobo.hoyoagent.mihoyo.checkin.CheckinService;
import com.luobo.hoyoagent.mihoyo.credential.AccountService;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * 签到工具：身份来自 {@link SessionRegistry} 注册的平台用户，不接受任何"签谁"的参数。
 *
 * <p>没有身份就直接拒绝，绝不回退到默认账号——匿名 HTTP 调用者不该能签 owner 的号。
 */
@Component
public class CheckinTool {

    private static final Logger log = LoggerFactory.getLogger(CheckinTool.class);

    /** 没有平台身份时的统一回复。 */
    static final String NO_IDENTITY = "没有绑定米游社账号，无法签到。请绑定后重试";

    private final ToolCallRecorder recorder;

    private final CheckinService checkinService;

    private final SessionRegistry sessions;

    private final AccountService accounts;

    public CheckinTool(ToolCallRecorder recorder,
                       CheckinService checkinService,
                       SessionRegistry sessions,
                       AccountService accounts) {
        this.recorder = recorder;
        this.checkinService = checkinService;
        this.sessions = sessions;
        this.accounts = accounts;
    }

    @Tool("帮当前绑定账号完成米游社每日签到（原神/星穹铁道/绝区零）。"
            + "用户说「签到」「帮我领原石」「签一下」时调用；幂等，今天已签会如实返回「已经签过」。"
            + "只是问「签到了吗 / 漏了几天」时不要调用本工具，改用 getCheckinOverview。"
            + "只能签当前账号，不接受指定其他玩家")
    public String checkinToday(@ToolMemoryId String memoryId) {
        String userId = sessions.userIdOf(memoryId).orElse(null);
        if (userId == null) {
            log.info("[TOOL] checkinToday 无身份会话，拒绝");
            return NO_IDENTITY;
        }
        log.info("[TOOL] checkinToday user={}", Mask.id(userId));
        recorder.record("checkinToday", "");

        CheckinRunResult summary;
        try {
            summary = checkinService.checkinAll(accounts.requireCredential(userId));
        } catch (BusinessException e) {
            return "【结论】签到失败：" + e.getMessage();
        }

        if (summary.results().isEmpty()) {
            return "【结论】" + summary.message();
        }

        String lines = summary.results().stream()
                .map(r -> "  %s（%s）：%s · 累计 %d 天%s".formatted(
                        r.gameName(),
                        r.uid(),
                        describe(r),
                        r.totalDays(),
                        r.reward().isBlank() ? "" : " · 奖励" + r.reward()))
                .collect(Collectors.joining("\n"));

        return "【结论】%s\n【数据】\n%s".formatted(summary.message(), lines);
    }

    private static String describe(CheckinRoleResult result) {
        return switch (result.status()) {
            case "SIGNED" -> "签到成功";
            case "ALREADY" -> "今天已经签过";
            default -> result.message();
        };
    }
}
