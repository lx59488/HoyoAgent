package com.luobo.hoyoagent.agent.tool;

import com.luobo.hoyoagent.agent.observability.ToolCallRecorder;
import com.luobo.hoyoagent.common.exception.BusinessException;
import com.luobo.hoyoagent.mihoyo.checkin.CheckinService;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * 签到工具：身份同样来自 cookie，不接受任何"签谁"的参数。
 *
 * <p>工具只负责把结果说清楚，幂等与重试都在 {@link CheckinService} 里——
 * 模型不该关心"今天是不是已经签过"，它只要照实汇报。
 */
@Component
public class CheckinTool {

    private static final Logger log = LoggerFactory.getLogger(CheckinTool.class);

    private final ToolCallRecorder recorder;

    private final CheckinService checkinService;

    public CheckinTool(ToolCallRecorder recorder, CheckinService checkinService) {
        this.recorder = recorder;
        this.checkinService = checkinService;
    }

    @Tool("帮当前绑定账号完成米游社每日签到（原神/星穹铁道/绝区零）。用户说「签到」「帮我领原石」「今天签到了吗」时调用。"
            + "只能签当前账号，不接受指定其他玩家")
    public String checkinToday() {
        log.info("[TOOL] checkinToday");
        recorder.record("checkinToday", "");

        CheckinService.Summary summary;
        try {
            summary = checkinService.checkinAll();
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

    private static String describe(CheckinService.RoleResult result) {
        return switch (result.status()) {
            case "SIGNED" -> "签到成功";
            case "ALREADY" -> "今天已经签过";
            default -> result.message();
        };
    }
}
