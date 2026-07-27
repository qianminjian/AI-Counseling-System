package com.mindsafe.ai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
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

    public ResilientChatModel(ChatModel primary, ChatModel fallback,
                              String primaryName, String fallbackName) {
        this.primary = primary;
        this.fallback = fallback;
        this.primaryName = primaryName;
        this.fallbackName = fallbackName;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        try {
            return primary.call(prompt);
        } catch (Exception e) {
            log.warn("LLM 主模型 [{}] 调用失败，降级到备用模型 [{}]: {}",
                    primaryName, fallbackName, e.getMessage());
            try {
                return fallback.call(prompt);
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
                    log.warn("LLM 主模型 [{}] 流式调用失败，降级到备用模型 [{}]: {}",
                            primaryName, fallbackName, e.getMessage());
                    return fallback.stream(prompt)
                            .onErrorResume(fallbackEx -> {
                                log.error("LLM 备用模型 [{}] 流式也失败: {}", fallbackName, fallbackEx.getMessage());
                                return Flux.error(e);
                            });
                });
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return primary.getDefaultOptions();
    }
}
