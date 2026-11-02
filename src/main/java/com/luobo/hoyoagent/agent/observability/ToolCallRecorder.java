package com.luobo.hoyoagent.agent.observability;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ToolCallRecorder {

    private final ThreadLocal<List<String>> calls = ThreadLocal.withInitial(ArrayList::new);

    public void record(String toolName, String args) {
        calls.get().add(toolName + "(" + args + ")");
    }

    /** 取出并清空。必须在 finally 里调用，见类注释。 */
    public List<String> drain() {
        List<String> snapshot = List.copyOf(calls.get());
        calls.get().clear();
        return snapshot;
    }
}