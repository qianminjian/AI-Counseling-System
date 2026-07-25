package com.mindsafe.ai.config;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

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

    /**
     * Layer2 输出审查专用线程池（小线程池：审查为低频异步任务，fire-and-forget）。
     * <p>
     * 与主对话流隔离，确保 SAF-002 异步 LLM 调用绝不阻塞流式输出。
     */
    @Bean(name = "outputReviewExecutor")
    public Executor outputReviewExecutor() {
        return Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "output-review");
            t.setDaemon(true);
            return t;
        });
    }
}
