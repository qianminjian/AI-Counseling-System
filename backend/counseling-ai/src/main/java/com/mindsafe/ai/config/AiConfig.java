package com.mindsafe.ai.config;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 模块配置
 * <p>
 * M1：MessageWindowChatMemory + InMemoryChatMemoryRepository（开发阶段，重启丢失）。
 * M2+：替换为 JDBC/Redis 持久化实现。
 */
@Configuration
public class AiConfig {

    /** 上下文窗口大小：保留最近 N 条消息 */
    private static final int MEMORY_WINDOW = 20;

    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(MEMORY_WINDOW)
                .build();
    }
}
