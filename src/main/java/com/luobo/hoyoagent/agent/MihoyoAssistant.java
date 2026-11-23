package com.luobo.hoyoagent.agent;

import dev.langchain4j.service.*;
import dev.langchain4j.service.memory.ChatMemoryAccess;
import dev.langchain4j.service.spring.AiService;

@AiService
public interface MihoyoAssistant extends ChatMemoryAccess {

    /**
     * system prompt 不在这里——它在 {@code agent.prompt.SystemPrompt}：分段、有版本号、
     * 能力清单与 {@code Capability} 注册表对齐。
     *
     * <p>这里只剩接线：{@code {{system}}} 由 {@code AgentChatService} 传入拼好的文本。
     * <b>别把文字写回注解</b>——那就又回到"改一个字要重编译、人设和能力互相牵制"。
     *
     * <p>{@code @MemoryId} 是会话身份、{@code @UserMessage} 是本轮问题，两者都不能少。
     *
     * <p>历史（说明为什么 {@code CapabilityConsistencyTest} 必须存在）：
     * 2026-11-07 提示词写着深渊战绩但工具没有；2026-11-12 上了签到、提示词还写着"没有签到能力"，
     * 模型直接拒绝用户；2026-11-13 删了角色工具、提示词还留着。
     */
    @SystemMessage("{{system}}")
    Result<String> chat(@MemoryId String sessionId,
                        @V("system") String system,
                        @UserMessage String userMessage);
}
