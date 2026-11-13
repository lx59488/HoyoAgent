package com.luobo.hoyoagent.agent.memory;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把"会话 id → 记忆"翻译成框架认识的 ChatMemoryProvider。
 *
 * <p>只要容器里恰好一个 ChatMemoryProvider、且没有任何 ChatMemory bean，
 * 启动时的 BeanFactoryPostProcessor 就会把它自动接到 @AiService 上 —— 注解一个字都不用改。
 * 两种情况都会在启动时直接炸，而且是中文项目里最难搜的那类错：
 * <ul>
 *   <li>多个 ChatMemoryProvider → "Conflict: multiple beans of type ..."</li>
 *   <li>同时有 ChatMemory 和 ChatMemoryProvider → "Only one ... can be configured"</li>
 * </ul>
 */
@Configuration
public class MemoryConfig {

    @Bean
    public ChatMemoryProvider chatMemoryProvider(SessionMemoryStore store, MemoryProperties properties) {
        return memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(properties.maxMessages())
                .chatMemoryStore(store)
                .build();
    }
}