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
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

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

    // ==================== 主模型配置（doing/63：供应商无关，key 主） ====================
    // 环境变量回退链在 application.yml 处理（LLM_PRIMARY_* → LLM_* → DEEPSEEK_* → 占位），此处读最终值
    @Value("${spring.ai.openai.api-key:sk-placeholder}")
    private String primaryApiKey;

    @Value("${spring.ai.openai.base-url:https://api.deepseek.com}")
    private String primaryBaseUrl;

    @Value("${spring.ai.openai.chat.options.model:deepseek-v4-flash}")
    private String primaryModel;

    @Value("${spring.ai.openai.chat.options.temperature:0.7}")
    private double primaryTemperature;

    @Value("${spring.ai.openai.chat.options.max-tokens:2048}")
    private int primaryMaxTokens;

    // ==================== 备份模型配置（doing/63：key 备；原 mindsafe.ai.fallback 段改名 mindsafe.llm.backup） ====================
    @Value("${mindsafe.llm.backup.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String backupBaseUrl;

    @Value("${mindsafe.llm.backup.api-key:}")
    private String backupApiKey;

    @Value("${mindsafe.llm.backup.model:qwen-plus}")
    private String backupModel;

    @Value("${mindsafe.llm.backup.temperature:0.7}")
    private double backupTemperature;

    @Value("${mindsafe.llm.backup.max-tokens:2048}")
    private int backupMaxTokens;

    /**
     * 弹性 ChatModel：主模型 + 备份模型自动降级（doing/63：主/备均为手动构建，任意双 OpenAI 兼容供应商）。
     * <p>
     * 启用条件：LLM_BACKUP_API_KEY 非空即双模型；为空返回主模型单例（行为兼容）。
     */
    @Bean
    @Primary
    public ChatModel resilientChatModel(MeterRegistry meterRegistry) {
        OpenAiChatModel primaryChatModel = buildChatModel(
                primaryApiKey, primaryBaseUrl, primaryModel, primaryTemperature, primaryMaxTokens);

        if (backupApiKey.isBlank()) {
            log.info("LLM 备份模型未配置（LLM_BACKUP_API_KEY 为空），使用单模型模式: 主=[{}]", primaryModel);
            return primaryChatModel;
        }

        log.info("LLM 多模型降级已启用: 主=[{}] 备=[{}]", primaryModel, backupModel);

        OpenAiChatModel backupChatModel = buildChatModel(
                backupApiKey, backupBaseUrl, backupModel, backupTemperature, backupMaxTokens);

        return new ResilientChatModel(
                primaryChatModel, backupChatModel,
                primaryModel, backupModel, meterRegistry);
    }

    /**
     * 构建单个供应商的 ChatModel（主/备对称，OpenAI 兼容协议）
     */
    private OpenAiChatModel buildChatModel(String apiKey, String baseUrl, String model,
                                           double temperature, int maxTokens) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .build();
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
     * BA-15：ThreadPoolTaskExecutor + TenantContextTaskDecorator 统一传播租户上下文
     * （历史 A1 手动捕获已收敛至装饰器，业务代码不再内嵌捕获/恢复）。
     */
    @Bean(name = "outputReviewExecutor")
    public Executor outputReviewExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setThreadNamePrefix("output-review-");
        executor.setTaskDecorator(new TenantContextTaskDecorator());
        // ThreadPoolTaskExecutor 实现 InitializingBean，由 Spring 生命周期统一 initialize（避免显式调用产生双池）
        return executor;
    }
}
