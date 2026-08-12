package com.mindsafe.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindsafe.ai.memory.RedisChatMemoryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
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

    // ==================== Embedding 配置（RAG 知识检索 KB-101） ====================
    // BUG-LLM-03：独立变量（EMBEDDING_*，compose 层透传）——生产未配置时回退主对话供应商
    // （DeepSeek 无 embeddings 端点）→ 404 静默失败，知识注入从未生效。值暂与 ASR 同源，用户后续自行切换供应商。
    @Value("${spring.ai.openai.embedding.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String embeddingBaseUrl;

    @Value("${spring.ai.openai.embedding.api-key:}")
    private String embeddingApiKey;

    @Value("${spring.ai.openai.embedding.options.model:text-embedding-v4}")
    private String embeddingModel;

    @Value("${spring.ai.openai.embedding.options.dimensions:1536}")
    private int embeddingDimensions;

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
                .baseUrl(normalizeBaseUrl(baseUrl))
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

    /**
     * BUG-LLM-01：规整供应商 base-url——Spring AI 1.0.0 的 {@code OpenAiApi} 默认
     * {@code completionsPath="/v1/chat/completions"}，baseUrl 已含 {@code /v1}（MiniMax/DashScope
     * 官方 OpenAI 兼容端点均带）时会拼出 {@code /v1/v1/...} 双前缀 → 生产实证 404 page not found
     * （备用模型降级全部失败）；DeepSeek（不带 /v1）不受影响。统一剥离尾部 /v1，由框架补全。
     */
    static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return baseUrl;
        }
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return trimmed.endsWith("/v1") ? trimmed.substring(0, trimmed.length() - 3) : trimmed;
    }

    /**
     * BUG-LLM-03：RAG 知识检索 embedding 模型（手动构建，与 chat 模型对称应用 normalizeBaseUrl）。
     * <p>
     * 关键约束：{@code knowledge_chunks.embedding} 为 vector(1536)（V24），embedding 输出维度必须 1536；
     * DashScope text-embedding-v4 默认 1024 维，须显式 dimensions=1536（生产实测）。
     * 未配置 EMBEDDING_API_KEY 时仅告警不 fail-fast（RAG 检索失败安全降级，对话主线不受影响）。
     */
    @Bean
    @Primary
    public EmbeddingModel embeddingModel() {
        if (embeddingApiKey.isBlank()) {
            log.warn("LLM embedding 未配置（EMBEDDING_API_KEY 为空），RAG 向量检索将不可用");
        }
        log.info("LLM embedding 模型: [{}] dims={} baseUrl={}", embeddingModel, embeddingDimensions,
                normalizeBaseUrl(embeddingBaseUrl));
        return buildEmbeddingModel(embeddingBaseUrl, embeddingApiKey, embeddingModel, embeddingDimensions);
    }

    /** 构建 embedding 模型（OpenAI 兼容协议；static 便于单测断言 options） */
    static OpenAiEmbeddingModel buildEmbeddingModel(String baseUrl, String apiKey,
                                                    String model, int dimensions) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(normalizeBaseUrl(baseUrl))
                .apiKey(apiKey)
                .build();
        return new OpenAiEmbeddingModel(api, MetadataMode.EMBED, buildEmbeddingOptions(model, dimensions));
    }

    /** embedding options：显式模型名与维度（static 便于单测断言） */
    static OpenAiEmbeddingOptions buildEmbeddingOptions(String model, int dimensions) {
        return OpenAiEmbeddingOptions.builder()
                .model(model)
                .dimensions(dimensions)
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

    /**
     * 辅助 LLM 调用专用线程池（doing/92 Q-005：同步辅助调用统一超时）。
     * <p>
     * P1-3 板块02：原 AiChatServiceImpl 自建静态 daemon 池（newFixedThreadPool(4)）
     * 收敛为受管 Bean——线程数保持既有 4 核行为；由 Spring 生命周期统一关闭
     * （ThreadPoolTaskExecutor 实现 DisposableBean，@PreDestroy 自动 shutdown）；
     * BA-15：经 TenantContextTaskDecorator 传播租户上下文（与专题 D 已修
     * TaskDecorator 机制一致，异步出口不再丢上下文）。
     */
    @Bean(name = "llmAuxExecutor")
    public Executor llmAuxExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(4);
        executor.setThreadNamePrefix("llm-aux-");
        executor.setTaskDecorator(new TenantContextTaskDecorator());
        return executor;
    }

    /**
     * 语义风险分类专用线程池（RISK-202：分诊段延迟门禁 ≤800ms）。
     * <p>
     * P1-3 板块02：原 SemanticRiskClassifier 构造器自建 daemon 池收敛为受管 Bean——
     * 线程数保持既有 max(2, availableProcessors) 行为；由 Spring 生命周期统一关闭；
     * BA-15：经 TenantContextTaskDecorator 传播租户上下文（语义分类可能触发
     * DB/记忆写入，缺上下文将触发租户行隔离 fail-fast）。
     */
    @Bean(name = "semanticRiskExecutor")
    public Executor semanticRiskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int size = Math.max(2, Runtime.getRuntime().availableProcessors());
        executor.setCorePoolSize(size);
        executor.setMaxPoolSize(size);
        executor.setThreadNamePrefix("semantic-risk-");
        executor.setTaskDecorator(new TenantContextTaskDecorator());
        return executor;
    }
}
