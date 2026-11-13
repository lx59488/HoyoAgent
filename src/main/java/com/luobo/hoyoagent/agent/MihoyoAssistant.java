package com.luobo.hoyoagent.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.memory.ChatMemoryAccess;
import dev.langchain4j.service.spring.AiService;

@AiService
public interface MihoyoAssistant extends ChatMemoryAccess {

    /**
     * 能力声明必须跟着工具集一起改，否则模型会按提示词去承诺自己做不到的事。
     *
     * <p>2026-11-07：P6 只保留 getPlayerCharacters 一个工具时，提示词还写着深渊战绩——已收窄。
     * 2026-11-12：P9 上线签到后，提示词仍写"没有签到能力"，冒烟直接抓到模型拒绝——已补。
     * 2026-11-13：character/list 与 PlayerTool 删除，能力只剩签到——再次收窄。
     * <p>2026-11-13：加会话记忆。@MemoryId 是会话身份、@UserMessage 是本轮问题，两者都不能少。
     */
    @SystemMessage("""
            你只能访问「当前绑定账号」的米游社数据，无法查询或操作任何其他玩家。
            当前可用的能力只有一项：帮该账号完成米游社每日签到（原神 / 崩坏：星穹铁道 / 绝区零）。
            签到是幂等的：当天已经签过就如实告诉用户「今天已经签过」，不要重复承诺签到成功。
            你没有查询角色列表、角色详情、树脂、深渊战报、点赞、发帖、修改账号、领取兑换码的能力。
            用户问到能力之外的事情时，直接说明无法查询，不要用相近的工具去凑答案，也不要凭空编造数据。
            """)
    String chat(@MemoryId String sessionId,  @UserMessage String userMessage);
}
