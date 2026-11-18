package com.luobo.hoyoagent.adapter.http;

import com.luobo.hoyoagent.agent.AgentChatService;
import com.luobo.hoyoagent.agent.MihoyoAssistant;
import com.luobo.hoyoagent.agent.memory.SessionMemoryStore;
import com.luobo.hoyoagent.agent.observability.ToolCallRecorder;
import com.luobo.hoyoagent.common.exception.ErrorCode;
import com.luobo.hoyoagent.common.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class AgentController {

    /** 不带 X-Session-Id 时用的一次性会话前缀：请求结束即销毁，行为和加记忆之前完全一致。 */
    private static final String ONE_SHOT_PREFIX = "one-shot:";

    private final AgentChatService chat;

    public AgentController(AgentChatService chat) {
        this.chat = chat;
    }

    /**
     * 会话身份由调用方（适配器）给，Agent 只当不透明字符串用：
     * 浏览器给 UUID，将来 onebot 给 "{platform}:{scope}:{id}"。
     *
     * <p>两条约定：
     * <ul>
     *   <li>不传 header = 不要记忆，而不是"给个 default 会话"——后者会让所有匿名调用串进同一个对话；</li>
     *   <li>一次性会话必须同时清 store 和框架缓存，否则每个无会话请求都会在框架的 map 里留一个死条目。</li>
     * </ul>
     */
    @GetMapping("/agent")
    public Result<AgentAnswer> agent(
            @RequestParam("q") String q,
            @RequestHeader(name = "X-Session-Id", required = false) String sessionId) {

        boolean oneShot = sessionId == null || sessionId.isBlank();
        String id = oneShot ? ONE_SHOT_PREFIX + UUID.randomUUID() : sessionId.trim();
        try {
            AgentChatService.Answer answer = chat.ask(id, q);
            return Result.success(new AgentAnswer(answer.reply(), answer.toolsCalled(), id, answer.messages()));
        } catch (Exception e) {
            return Result.error(ErrorCode.INTERNAL_ERROR,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (oneShot) {
                chat.evict(id);
            }
        }
    }

    public record AgentAnswer(String reply, List<String> toolsCalled, String sessionId, int messages) {
    }
}
