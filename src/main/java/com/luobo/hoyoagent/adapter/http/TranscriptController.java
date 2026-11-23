package com.luobo.hoyoagent.adapter.http;

import com.luobo.hoyoagent.agent.transcript.TranscriptPage;
import com.luobo.hoyoagent.agent.transcript.TranscriptSession;
import com.luobo.hoyoagent.agent.transcript.TranscriptStore;
import com.luobo.hoyoagent.common.result.ApiResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 聊天历史的只读观测口。
 *
 * <p><b>刻意没有 POST / DELETE 映射</b>：历史由 {@code AgentChatService} 在每轮结束时追加，
 * 调试台只能看。清空会话记忆（{@code DELETE /api/memory/{id}}）也不会删这里——
 * 那是"下一轮发给模型的东西"，不是"用户聊过什么"。
 *
 * <p>读取不 touch 会话、不续 TTL，和 {@code MemoryController} 的 {@code snapshot/expired} 同一约定。
 */
@RestController
@RequestMapping("/api/transcripts")
public class TranscriptController {

    private final TranscriptStore transcripts;

    public TranscriptController(TranscriptStore transcripts) {
        this.transcripts = transcripts;
    }

    /** 按最后聊天时间倒序的会话列表。 */
    @GetMapping
    public ApiResult<List<TranscriptSession>> sessions() {
        return ApiResult.success(transcripts.sessions());
    }

    /**
     * 读一个会话的历史，seq 升序。
     *
     * @param before 只取 seq 更小的轮次；0 = 从最新往前
     * @param limit  最多返回多少轮（内部夹到 1~500）
     */
    @GetMapping("/{sessionId}")
    public ApiResult<TranscriptPage> read(
            @PathVariable("sessionId") String sessionId,
            @RequestParam(name = "before", defaultValue = "0") long before,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return ApiResult.success(transcripts.read(sessionId, before, limit));
    }
}
