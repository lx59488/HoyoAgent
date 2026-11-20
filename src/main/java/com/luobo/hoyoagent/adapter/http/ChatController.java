package com.luobo.hoyoagent.adapter.http;

import com.luobo.hoyoagent.common.exception.ErrorCode;
import com.luobo.hoyoagent.common.result.ApiResult;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatController {

    private final ChatModel chatModel;

    public ChatController(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /** 纯模型问答（对照组：不挂工具）。 */
    @GetMapping("/chat")
    public ApiResult<ChatReply> chat(@RequestParam("q") String q) {
        try {
            return ApiResult.success(new ChatReply(chatModel.chat(q)));
        } catch (Exception e) {
            return ApiResult.error(ErrorCode.AI_SERVICE_ERROR, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    public record ChatReply(String reply) {
    }
}
