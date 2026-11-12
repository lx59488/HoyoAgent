package com.luobo.hoyoagent.adapter.http;

import com.luobo.hoyoagent.agent.MihoyoAssistant;
import com.luobo.hoyoagent.agent.observability.ToolCallRecorder;
import com.luobo.hoyoagent.common.exception.ErrorCode;
import com.luobo.hoyoagent.common.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class AgentController {

    private final MihoyoAssistant assistant;
    private final ToolCallRecorder recorder;

    public AgentController(MihoyoAssistant assistant, ToolCallRecorder recorder) {
        this.assistant = assistant;
        this.recorder = recorder;
    }

    @GetMapping("/agent")
    public Result<AgentAnswer> agent(@RequestParam("q") String q) {
        try {
            String reply = assistant.chat(q);
            return Result.success(new AgentAnswer(true, reply, recorder.drain()));
        } catch (Exception e) {
            return Result.error(ErrorCode.INTERNAL_ERROR, e.getClass().getSimpleName());
        }
    }

    /** 唯一出口：drain 只在这里调一次 —— 一定被取走，也一定被清空 */
    private Map<String, Object> finish(boolean ok, String reply, Exception e) {
        List<String> calls = recorder.drain();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", ok);
        if (ok) {
            body.put("reply", reply);
        } else {
            body.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        body.put("toolsCalled", calls);
        return body;
    }


    public record AgentAnswer(boolean ok, String reply, List<String> toolsCalled) {}

}
