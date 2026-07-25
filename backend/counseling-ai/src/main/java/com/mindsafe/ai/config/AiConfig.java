package com.mindsafe.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindsafe.ai.memory.RedisChatMemoryRepository;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * AI 模块配置
 * <p>
 * ChatMemory：Redis 持久化（TTL=2h），重启不丢失对话上下文。
 * 替代 M1 的 InMemoryChatMemoryRepository。
 */
@Configuration
public class AiConfig {

    /** 上下文窗口大小：保留最近 N 条消息 */
    private static final int MEMORY_WINDOW = 20;

    @Bean
    public RedisChatMemoryRepository redisChatMemoryRepository(StringRedisTemplate redisTemplate,
                                                               ObjectMapper objectMapper) {
        return new RedisChatMemoryRepository(redisTemplate, objectMapper);
    }

    @Bean
    public ChatMemory chatMemory(RedisChatMemoryRepository redisChatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(redisChatMemoryRepository)
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
