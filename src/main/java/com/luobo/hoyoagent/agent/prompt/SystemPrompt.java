package com.luobo.hoyoagent.agent.prompt;

import java.util.Map;

/**
 * system prompt 的唯一组装点（对应 C1）。
 *
 * <p>以前它是 {@code @SystemMessage} 里的一整段注解文本：改一个字要重编译，能力声明和语气互相牵制，
 * 也没有地方放"本轮是几号"这种动态信息。现在分四段（见 {@link PromptSection}），
 * 由本类按固定顺序拼成一段带标签的文本，再交给 {@code MihoyoAssistant} 的 {@code @V} 参数。
 *
 * <p>三条规矩：
 * <ul>
 *   <li><b>空段整段不输出</b>：{@code <persona></persona>} 这种空标签会让模型以为"这里本该有内容"；</li>
 *   <li><b>顺序由枚举定</b>，不由调用方传入的 Map 定；</li>
 *   <li><b>总长有上限</b>：窗口只有 20 条，prompt 每多 100 字是每轮都付，还会挤掉对话轮次。</li>
 * </ul>
 */
public class SystemPrompt {

    /**
     * prompt 版本。改任何一段的文字都要 +1，否则事后回放日志时无法归因（C4 的 {@code promptVersion}）。
     */
    public static final String VERSION = "p2";

    /**
     * 1. 人设 / 语气。
     *
     * <p>现在留空：内容是 P13 的事，而且要等 C4 的评测基线——先有人设再谈"改得好不好"没有意义。
     * 填的时候从项目里已有的**人写文案**归纳（`QqBotDispatcher` 的固定回复、`CheckinMessages`），
     * 不要发明一个人设。
     */
    public static final String PERSONA = "";

    /**
     * 2. 能力声明。随工具集变——本段每一句都必须能在 {@code Capability} 注册表里找到出处，
     * 由 {@code CapabilityConsistencyTest} 守着。
     */
    public static final String CAPABILITIES = """
            你只能访问「当前绑定账号」的米游社数据，无法查询或操作任何其他玩家。
            当前可用的能力有两项：
            1. 帮该账号完成米游社每日签到（原神 / 崩坏：星穹铁道 / 绝区零），也可以只签指定的一个游戏；
            2. 查看该账号的签到总览（今天各游戏签没签、累计天数、本月约漏几天、最近 7 天结果），也可以只看指定的一个游戏。
            签到是幂等的：当天已经签过就如实告诉用户「今天已经签过」，不要重复承诺签到成功。
            「帮我签」是执行动作，用签到工具；「签到了吗 / 漏了几天」是查看，用签到总览，不要用总览去签。
            用户只提到一个游戏时，把那个游戏传给工具的 game 参数；没提或提到多个游戏时不传，按全部处理。
            你没有查询角色列表、角色详情、树脂、深渊战报、点赞、发帖、修改账号、领取兑换码的能力。
            用户问到能力之外的事情时，直接说明无法查询，不要用相近的工具去凑答案，也不要凭空编造数据。
            """;

    /**
     * 3. 通道文体。QQ 是纯文本：单条 ≤600 字、同一 msg_id 最多 4 条、markdown 不会渲染。
     *
     * <p>现在留空：写它属于改提示词文字（C1-b），必须先有 C4 的基线才能判断"改了是变好还是变坏"。
     */
    public static final String CHANNEL = "";

    /** 总长上限：4000 字。超了就抛，而不是悄悄截断——截断会让某一段永远看不到。 */
    static final int MAX_CHARS = 4000;

    public static String compose() {
        return compose("");
    }

    /** 生产入口：只用常量段 + 本轮动态信息。 */
    public static String compose(String context) {
        return compose(Map.of(
                PromptSection.PERSONA, PERSONA,
                PromptSection.CAPABILITIES, CAPABILITIES,
                PromptSection.CHANNEL, CHANNEL,
                PromptSection.CONTEXT, context == null ? "" : context));
    }


    /**
     * 通用入口：缺的段当作空段。
     *
     * @param sections 各段正文，可以为空串或 null（都会被当成"没有这一段"）
     * @return 按 {@link PromptSection} 声明顺序拼接、每段用 XML 标签包裹的文本
     * @throws IllegalStateException 拼出来的总长超过 {@link #MAX_CHARS}
     */
    public static String compose(Map<PromptSection, String> sections) {
        StringBuilder sb = new StringBuilder();
        for (PromptSection section : PromptSection.values()) {
            append(sb, section, sections.get(section));
        }
        String prompt = sb.toString().strip();
        if (prompt.length() > MAX_CHARS) {
            throw new IllegalStateException(
                    "system prompt 过长（" + prompt.length() + " > " + MAX_CHARS + " 字）："
                            + "每轮都要付这份钱，还会挤掉记忆窗口。先压文字，别先调大上限");
        }
        return prompt;
    }
    private static void append(StringBuilder sb, PromptSection section, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append('\n');
        }
        sb.append('<').append(section.tag()).append('>').append('\n')
                .append(text.strip()).append('\n')
                .append("</").append(section.tag()).append('>');
    }

    private SystemPrompt() {
    }
}
