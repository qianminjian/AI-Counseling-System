package com.mindsafe.ai.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.DefaultChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

/**
 * 弹性 ChatModel 装饰器：主模型调用失败时自动降级到备用模型。
 * <p>
 * 降级策略：
 * - 主模型抛出任何异常（超时/限流/服务不可用）→ 立即切换备用模型
 * - 备用模型也失败 → 抛出原始异常（由上层 GlobalExceptionHandler 处理）
 * - 降级事件记录 WARN 日志，便于监控告警
 */
public class ResilientChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(ResilientChatModel.class);

    private final ChatModel primary;
    private final ChatModel fallback;
    private final String primaryName;
    private final String fallbackName;
    private final Counter modelFallbackCounter;

    public ResilientChatModel(ChatModel primary, ChatModel fallback,
                              String primaryName, String fallbackName,
                              MeterRegistry meterRegistry) {
        this.primary = primary;
        this.fallback = fallback;
        this.primaryName = primaryName;
        this.fallbackName = fallbackName;
        this.modelFallbackCounter = Counter.builder("mindsafe.llm.model_fallback")
                .description("LLM 主模型降级到备用模型次数")
                .tag("from", primaryName)
                .tag("to", fallbackName)
                .register(meterRegistry);
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        try {
            return primary.call(prompt);
        } catch (Exception e) {
            modelFallbackCounter.increment();
            log.warn("LLM 主模型 [{}] 调用失败，降级到备用模型 [{}]: {}",
                    primaryName, fallbackName, e.getMessage());
            try {
                return fallback.call(withFallbackModel(prompt));
            } catch (Exception fallbackEx) {
                log.error("LLM 备用模型 [{}] 也调用失败: {}", fallbackName, fallbackEx.getMessage());
                throw e; // 抛出原始异常，保留主模型错误信息
            }
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return primary.stream(prompt)
                .onErrorResume(e -> {
                    modelFallbackCounter.increment();
                    log.warn("LLM 主模型 [{}] 流式调用失败，降级到备用模型 [{}]: {}",
                            primaryName, fallbackName, e.getMessage());
                    return fallback.stream(withFallbackModel(prompt))
                            .onErrorResume(fallbackEx -> {
                                log.error("LLM 备用模型 [{}] 流式也失败: {}", fallbackName, fallbackEx.getMessage());
                                return Flux.error(e);
                            });
                });
    }

    /**
     * BUG-LLM-02：降级转发前将 Prompt 的 model 替换为备用模型名——ChatClient 调用链会把主模型
     * defaultOptions（如 deepseek-v4-pro）合并进 Prompt.options，直接转发会被备用供应商以
     * unknown model 拒绝（生产实证：MiniMax 400 invalid params, unknown model 'deepseek-v4-pro' (2013)）。
     * 副本仅替换 model，其余参数（temperature 等）由备用模型 defaultOptions 合并兜底。
     */
    private Prompt withFallbackModel(Prompt prompt) {
        ChatOptions options = prompt.getOptions();
        if (options == null || fallbackName == null || fallbackName.isBlank()
                || fallbackName.equals(options.getModel())) {
            return prompt;
        }
        ChatOptions copy = options.copy();
        if (copy instanceof DefaultChatOptions defaultOptions) {
            defaultOptions.setModel(fallbackName);
            return new Prompt(prompt.getInstructions(), defaultOptions);
        }
        if (copy instanceof OpenAiChatOptions openAiOptions) {
            openAiOptions.setModel(fallbackName);
            return new Prompt(prompt.getInstructions(), openAiOptions);
        }
        return prompt;
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return primary.getDefaultOptions();
    }
}
