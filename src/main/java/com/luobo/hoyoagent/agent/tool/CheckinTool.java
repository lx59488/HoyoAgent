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
        // 先记再判身份：被拒的调用也是一次调用（模型选中了它），不记就看不见——评测基线靠这一行才有数据
        recorder.record("checkinToday", "");
        String userId = sessions.userIdOf(memoryId).orElse(null);
        if (userId == null) {
            log.info("[TOOL] checkinToday 无身份会话，拒绝");
            return NO_IDENTITY;
        }
        log.info("[TOOL] checkinToday user={}", Mask.id(userId));

        CheckinRunResult summary;
        try {
            summary = checkinService.checkinAll(accounts.requireCredential(userId));
        } catch (BusinessException e) {
            return "签到失败：" + e.getMessage();
        }

        return format(summary);
    }

    /**
     * 工具输出契约（C5）：<b>只给事实，不给措辞</b>。
     *
     * <ul>
     *   <li>不写「结论」——归纳是模型的事，工具给出结论就等于把语气从 Java 里写死；</li>
     *   <li>不出现内部枚举（{@code SIGNED} / {@code ALREADY} / {@code FAILED}），先翻成人话；</li>
     *   <li>一行一个角色，字段顺序固定；</li>
     *   <li>不用 {@code 【】}、markdown、表格——QQ 不渲染，模型也容易照抄。</li>
     * </ul>
     *
     * <p>唯一保留原句的是「没有可签到的角色」这条：{@code summary.message()} 说明了原因
     * （读不到角色 / 凭据失效），那是事实，不是措辞。
     */
    static String format(CheckinRunResult summary) {
        if (summary.results().isEmpty()) {
            String head = "签到结果：没有可签到的角色";
            return summary.message().isBlank() ? head : head + "\n原因：" + summary.message();
        }
        StringBuilder out = new StringBuilder("签到结果");
        for (CheckinRoleResult role : summary.results()) {
            out.append("\n- ").append(role.gameName()).append(' ').append(role.uid())
                    .append("：").append(describe(role));
        }
        return out.toString();
    }

    private static String describe(CheckinRoleResult role) {
        StringBuilder out = new StringBuilder(switch (role.status()) {
            case "SIGNED" -> "新签成功";
            case "ALREADY" -> "今天已签";
            case "FIRST_BIND" -> "首次绑定，今天不用签";
            case "CAPTCHA" -> "需要人机验证";
            default -> "签到失败";
        });
        if (role.totalDays() > 0) {
            out.append(" · 累计 ").append(role.totalDays()).append(" 天");
        }
        if (!role.reward().isBlank()) {
            out.append(" · 奖励 ").append(role.reward());
        }
        if (!role.ok()) {
            // 失败原因保留原始文案：那是接口给的，不是我们编的
            out.append("（").append(role.message()).append("）");
        }
        return out.toString();
    }
}