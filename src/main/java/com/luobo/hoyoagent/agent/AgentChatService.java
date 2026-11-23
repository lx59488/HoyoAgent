package com.luobo.hoyoagent.agent;

import com.luobo.hoyoagent.agent.memory.SessionMemoryStore;
import com.luobo.hoyoagent.agent.observability.ToolCallRecorder;
import com.luobo.hoyoagent.agent.observability.TurnTrace;
import com.luobo.hoyoagent.agent.prompt.SystemPrompt;
import dev.langchain4j.service.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class AgentChatService {

    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);

    private final MihoyoAssistant assistant;

    private final ToolCallRecorder recorder;

    private final SessionMemoryStore memory;


    public AgentChatService(MihoyoAssistant assistant, ToolCallRecorder recorder, SessionMemoryStore memory) {
        this.assistant = assistant;
        this.recorder = recorder;
        this.memory = memory;
    }

    /**
     * sessionId 由适配器给，这里只当不透明字符串。失败直接抛，由适配器翻译成"用户能看懂的话"。
     *
     * <p>每轮落一行 {@code [TRACE]}：token 用量来自 {@link Result}（返回值改成 {@code Result<String>}
     * 才拿得到——纯 String 里没有这个信息），延迟在这里掐表。
     */
    public Answer ask(String sessionId, String message) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        long startNanos = System.nanoTime();

        try {
            Result<String> result = assistant.chat(sessionId, SystemPrompt.compose(), message);
            List<String> tools = recorder.drain();
            log.info(TurnTrace.ok(traceId, sessionId, SystemPrompt.VERSION, tools,
                    result.tokenUsage(), elapsedMs(startNanos)).logLine());
            return new Answer(result.content(), tools, memory.snapshot(sessionId).size());
        } catch (RuntimeException e) {
            List<String> tools = recorder.drain();      // 不清就会串到下一轮
            log.warn(TurnTrace.failed(traceId, sessionId, SystemPrompt.VERSION, tools,
                    null, elapsedMs(startNanos), e.getClass().getSimpleName()).logLine());
            throw e;
        }
    }


    /** 一次性会话：store 和框架缓存都要清，否则框架 map 里留死条目。 */
    public void evict(String sessionId) {
        memory.deleteMessages(sessionId);
        assistant.evictChatMemory(sessionId);
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    public record Answer(String reply, List<String> toolsCalled, int messages) {
    }
}
