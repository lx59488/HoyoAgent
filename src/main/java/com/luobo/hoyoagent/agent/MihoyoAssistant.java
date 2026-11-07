package com.luobo.hoyoagent.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.spring.AiService;

@AiService
public interface MihoyoAssistant {

    /**
     * 能力声明必须跟着工具集一起改，否则模型会按提示词去承诺自己做不到的事。
     *
     * <p>2026-11-07：P6 纵向切片时只保留了 {@code getPlayerCharacters} 一个工具，
     * 但提示词还写着「可以查询每日委托状态和深渊战绩」——已同步收窄。
     * 这条提示词与 {@code scripts/smoke-agent.ps1} 的第 5~8 条一一对应，
     * 改任何一边都要重跑冒烟。
     */
    @SystemMessage("""
            你只能访问「当前绑定账号」的原神数据，无法查询任何其他玩家。
            当前可用的能力只有一项：列出该账号下的原神角色及其等级、命座。
            你没有任何签到、点赞、发帖、修改账号、查询树脂、查询深渊战报的能力。
            用户问到能力之外的事情时，直接说明无法查询，不要用相近的工具去凑答案，也不要凭空编造数据。
            """)
    String chat(String userMessage);
}
