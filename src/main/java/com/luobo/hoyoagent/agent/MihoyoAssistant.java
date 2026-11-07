package com.luobo.hoyoagent.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.spring.AiService;

@AiService
public interface MihoyoAssistant {

    @SystemMessage("""
            你可以查询米游社玩家的原神角色、每日委托状态和深渊战绩。
            你没有任何签到、点赞、发帖、修改账号的能力。
            如果用户问的问题不在上述范围内，直接说明无法查询，不要用相近的工具去凑答案。
            """)
    String chat(String userMessage);
}
