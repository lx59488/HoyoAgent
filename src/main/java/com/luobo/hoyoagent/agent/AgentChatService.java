package com.luobo.hoyoagent.agent;

import com.luobo.hoyoagent.agent.memory.SessionMemoryStore;
import com.luobo.hoyoagent.agent.observability.ToolCallRecorder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AgentChatService {

    private final MihoyoAssistant assistant;

    private final ToolCallRecorder recorder;

    private final SessionMemoryStore memory;


    public AgentChatService(MihoyoAssistant assistant, ToolCallRecorder recorder, SessionMemoryStore memory) {
        this.assistant = assistant;
        this.recorder = recorder;
        this.memory = memory;
    }

    /** sessionId 由适配器给，这里只当不透明字符串。失败直接抛，由适配器翻译成"用户能看懂的话"。 */
    public Answer ask(String sessionId, String message) {
        try {
            String reply = assistant.chat(sessionId, message);
            return new Answer(reply, recorder.drain(), memory.snapshot(sessionId).size());
        } catch (RuntimeException e) {
            recorder.drain();      // 不清就会串到下一轮
            throw e;
        }
    }


    /** 一次性会话：store 和框架缓存都要清，否则框架 map 里留死条目。 */
    public void evict(String sessionId) {
        memory.deleteMessages(sessionId);
        assistant.evictChatMemory(sessionId);
    }

    public record Answer(String reply, List<String> toolsCalled, int messages) {
    }
}
