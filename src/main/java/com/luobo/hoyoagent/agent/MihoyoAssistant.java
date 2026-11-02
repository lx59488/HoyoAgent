package com.luobo.hoyoagent.agent;

import dev.langchain4j.service.spring.AiService;

@AiService
public interface MihoyoAssistant {


    String chat(String userMessage);
}
