package com.luobo.hoyoagent.adapter.http;

import com.luobo.hoyoagent.agent.MihoyoAssistant;
import com.luobo.hoyoagent.agent.observability.ToolCallRecorder;
import com.luobo.hoyoagent.common.exception.ErrorCode;
import com.luobo.hoyoagent.common.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class AgentController {

    private final MihoyoAssistant assistant;

    private final ToolCallRecorder recorder;

    public AgentController(MihoyoAssistant assistant, ToolCallRecorder recorder) {
        this.assistant = assistant;
        this.recorder = recorder;
    }

    /** 唯一出口：drain 只在这里调一次 —— 一定被取走，也一定被清空。 */
    @GetMapping("/agent")
    public Result<AgentAnswer> agent(@RequestParam("q") String q) {
        try {
            String reply = assistant.chat(q);
            return Result.success(new AgentAnswer(reply, recorder.drain()));
        } catch (Exception e) {
            // 工具调用的观测结果也要取走，否则下一次请求会读到脏数据
            recorder.drain();
            return Result.error(ErrorCode.INTERNAL_ERROR,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    public record AgentAnswer(String reply, List<String> toolsCalled) {
    }
}
