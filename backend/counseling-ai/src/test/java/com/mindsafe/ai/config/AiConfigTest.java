package com.mindsafe.ai.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * AiConfig 主备模型装配测试（doing/63：LLM 主备配置通用化）
 * <p>
 * 覆盖 LLM-GEN-011：主备 api-key 条件分支（备空 → 单模型；备非空 → 双模型降级）。
 */
class AiConfigTest {

    private AiConfig config;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        config = new AiConfig();
        meterRegistry = new SimpleMeterRegistry();
        // 主模型默认配置（与 application.yml 回退链终值一致）
        ReflectionTestUtils.setField(config, "primaryApiKey", "sk-primary");
        ReflectionTestUtils.setField(config, "primaryBaseUrl", "https://api.deepseek.com");
        ReflectionTestUtils.setField(config, "primaryModel", "deepseek-v4-flash");
        ReflectionTestUtils.setField(config, "primaryTemperature", 0.7);
        ReflectionTestUtils.setField(config, "primaryMaxTokens", 2048);
        // 备模型默认：key 为空 = 单模型模式
        ReflectionTestUtils.setField(config, "backupApiKey", "");
        ReflectionTestUtils.setField(config, "backupBaseUrl", "https://dashscope.aliyuncs.com/compatible-mode/v1");
        ReflectionTestUtils.setField(config, "backupModel", "qwen-plus");
        ReflectionTestUtils.setField(config, "backupTemperature", 0.7);
        ReflectionTestUtils.setField(config, "backupMaxTokens", 2048);
    }

    @Test
    @DisplayName("备 key 为空（LLM_BACKUP_API_KEY 未配置）：返回单模型 OpenAiChatModel，非 ResilientChatModel")
    void backupKeyEmpty_returnsSingleModel() {
        ChatModel model = config.resilientChatModel(meterRegistry);
        assertInstanceOf(OpenAiChatModel.class, model);
        assertFalse(model instanceof ResilientChatModel);
    }

    @Test
    @DisplayName("备 key 非空（LLM_BACKUP_API_KEY 已配置）：返回 ResilientChatModel（双模型降级）")
    void backupKeyConfigured_returnsResilientModel() {
        ReflectionTestUtils.setField(config, "backupApiKey", "sk-backup");
        ChatModel model = config.resilientChatModel(meterRegistry);
        assertInstanceOf(ResilientChatModel.class, model);
    }

    @Test
    @DisplayName("BUG-LLM-01: base-url 含尾部 /v1（MiniMax/DashScope 形态）剥离，避免 /v1/v1 双前缀 404")
    void normalizeBaseUrl_stripsTrailingV1() {
        assertThat(AiConfig.normalizeBaseUrl("https://api.minimaxi.com/v1"))
                .isEqualTo("https://api.minimaxi.com");
        assertThat(AiConfig.normalizeBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1"))
                .isEqualTo("https://dashscope.aliyuncs.com/compatible-mode");
        assertThat(AiConfig.normalizeBaseUrl("https://api.minimaxi.com/v1/"))
                .isEqualTo("https://api.minimaxi.com");
    }

    @Test
    @DisplayName("BUG-LLM-01: base-url 不含 /v1（DeepSeek 形态）原样保留，尾斜杠去除")
    void normalizeBaseUrl_keepsRootUrl() {
        assertThat(AiConfig.normalizeBaseUrl("https://api.deepseek.com"))
                .isEqualTo("https://api.deepseek.com");
        assertThat(AiConfig.normalizeBaseUrl("https://api.deepseek.com/"))
                .isEqualTo("https://api.deepseek.com");
        assertThat(AiConfig.normalizeBaseUrl(null)).isNull();
    }

    @Test
    @DisplayName("BUG-LLM-03: embedding 构建应用模型名与 dimensions=1536（匹配 vector(1536)）")
    void buildEmbeddingModel_appliesOptions() {
        OpenAiEmbeddingOptions options = AiConfig.buildEmbeddingOptions("text-embedding-v4", 1536);
        assertThat(options.getModel()).isEqualTo("text-embedding-v4");
        assertThat(options.getDimensions()).isEqualTo(1536);
    }
}
