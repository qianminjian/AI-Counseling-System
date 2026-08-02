package com.mindsafe.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindsafe.ai.memory.RedisChatMemoryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * AI 模块配置
 * <p>
 * ChatMemory：Redis 持久化（TTL=2h），重启不丢失对话上下文。
 * ResilientChatModel：主模型失败自动降级备用模型（AI-004）。
 */
@Configuration
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    /** 上下文窗口大小：保留最近 N 条消息 */
    private static final int MEMORY_WINDOW = 20;

    // ==================== 备用模型配置（AI-004） ====================
    @Value("${mindsafe.ai.fallback.enabled:false}")
    private boolean fallbackEnabled;

    @Value("${mindsafe.ai.fallback.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String fallbackBaseUrl;

    @Value("${mindsafe.ai.fallback.api-key:}")
    private String fallbackApiKey;

    @Value("${mindsafe.ai.fallback.model:qwen-plus}")
    private String fallbackModel;

    @Value("${mindsafe.ai.fallback.temperature:0.7}")
    private double fallbackTemperature;

    @Value("${mindsafe.ai.fallback.max-tokens:2048}")
    private int fallbackMaxTokens;

    @Value("${spring.ai.openai.base-url:https://api.deepseek.com}")
    private String primaryBaseUrl;

    @Value("${spring.ai.openai.chat.options.model:deepseek-v4-flash}")
    private String primaryModel;

    /**
     * 弹性 ChatModel：主模型（DeepSeek）+ 备用模型（通义千问/GLM）自动降级。
     * <p>
     * 当 mindsafe.ai.fallback.enabled=true 且配置了备用 API Key 时启用。
     * 否则直接返回 Spring AI 自动配置的原始 ChatModel。
     */
    @Bean
    @Primary
    public ChatModel resilientChatModel(ChatModel autoConfiguredChatModel, MeterRegistry meterRegistry) {
        if (!fallbackEnabled || fallbackApiKey.isBlank()) {
            log.info("LLM 备用模型未启用（fallback.enabled={}），使用单模型模式", fallbackEnabled);
            return autoConfiguredChatModel;
        }

        log.info("LLM 多模型降级已启用: 主=[{}] 备=[{}]", primaryModel, fallbackModel);

        OpenAiApi fallbackApi = OpenAiApi.builder()
                .baseUrl(fallbackBaseUrl)
                .apiKey(fallbackApiKey)
                .build();

        OpenAiChatOptions fallbackOptions = OpenAiChatOptions.builder()
                .model(fallbackModel)
                .temperature(fallbackTemperature)
                .maxTokens(fallbackMaxTokens)
                .build();

        OpenAiChatModel fallbackChatModel = OpenAiChatModel.builder()
                .openAiApi(fallbackApi)
                .defaultOptions(fallbackOptions)
                .build();

        return new ResilientChatModel(
                autoConfiguredChatModel, fallbackChatModel,
                primaryModel, fallbackModel, meterRegistry);
    }

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
