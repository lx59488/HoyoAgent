package com.luobo.hoyoagent.adapter.http;

import dev.langchain4j.model.chat.ChatModel;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatController {

    @Resource
    private ChatModel chatModel;


    @GetMapping("/chat")
    public Answer chat(@RequestParam("q") String q) {
        try {
            return new Answer(true, chatModel.chat(q), null);
        } catch (Exception e) {
            return new Answer(false, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }


    public record Answer(boolean ok, String reply, String error) { }


}
