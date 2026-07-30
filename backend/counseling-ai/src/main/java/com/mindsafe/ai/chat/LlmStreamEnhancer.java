package com.mindsafe.ai.chat;

import com.mindsafe.common.dto.chat.StreamMessageEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LLM 流式调用性能增强（PERF-001）
 * <p>
 * 功能：
 * 1. 首 token 超时检测（默认 3s，目标 < 1s）
 * 2. 整体流超时（默认 60s）
 * 3. 超时降级：返回安全话术（不让学生等待无响应）
 * 4. 首 token 延迟指标日志（供 Prometheus 采集）
 */
@Component
public class LlmStreamEnhancer {

    private static final Logger log = LoggerFactory.getLogger(LlmStreamEnhancer.class);

    private final Duration firstTokenTimeout;
    private final Duration overallTimeout;

    /** 降级安全话术（面向小学生，温和不突兀） */
    private static final String FALLBACK_MESSAGE =
            "波波现在有点忙不过来，你先深呼吸三次，等一等波波好不好？我马上就回来陪你～";

    public LlmStreamEnhancer(
            @Value("${mindsafe.llm.first-token-timeout-ms:15000}") long firstTokenMs,
            @Value("${mindsafe.llm.overall-timeout-ms:60000}") long overallMs) {
        this.firstTokenTimeout = Duration.ofMillis(firstTokenMs);
        this.overallTimeout = Duration.ofMillis(overallMs);
        log.info("LLM 性能增强初始化: firstTokenTimeout={}ms, overallTimeout={}ms", firstTokenMs, overallMs);
    }

    /**
     * 包装原始 LLM 流，添加超时保护 + 降级 + 指标
     *
     * @param rawStream 原始 token 流
     * @param sessionId 会话 ID（日志关联）
     * @return 增强后的事件流
     */
    public Flux<StreamMessageEvent> enhance(Flux<StreamMessageEvent> rawStream, UUID sessionId) {
        AtomicLong startTime = new AtomicLong(System.currentTimeMillis());
        AtomicBoolean firstTokenReceived = new AtomicBoolean(false);

        return rawStream
                // 首 token 延迟监控
                .doOnNext(evt -> {
                    if ("token".equals(evt.type()) && firstTokenReceived.compareAndSet(false, true)) {
                        long latency = System.currentTimeMillis() - startTime.get();
                        log.info("LLM 首 token 延迟: sessionId={}, latency={}ms", sessionId, latency);
                        if (latency > 1000) {
                            log.warn("LLM 首 token 超过 1s 目标: sessionId={}, latency={}ms", sessionId, latency);
                        }
                    }
                })
                // 首 token 超时：如果第一个元素超过阈值，触发降级
                .timeout(firstTokenTimeout, Flux.defer(() -> {
                    log.warn("LLM 首 token 超时降级: sessionId={}, timeout={}ms", sessionId, firstTokenTimeout.toMillis());
                    return fallbackStream(sessionId, "first_token_timeout");
                }))
                // 整体流超时
                .timeout(overallTimeout)
                // 整体超时降级
                .onErrorResume(java.util.concurrent.TimeoutException.class, e -> {
                    log.warn("LLM 整体流超时降级: sessionId={}, timeout={}ms", sessionId, overallTimeout.toMillis());
                    return fallbackStream(sessionId, "overall_timeout");
                })
                // 其他异常降级（网络断开、模型服务不可用等）
                .onErrorResume(e -> {
                    if (e instanceof java.util.concurrent.TimeoutException) {
                        return Flux.empty(); // 已被上面处理
                    }
                    log.error("LLM 流异常降级: sessionId={}", sessionId, e);
                    return fallbackStream(sessionId, "error:" + e.getClass().getSimpleName());
                });
    }

    /**
     * 降级流：返回安全话术 token + done 事件
     */
    private Flux<StreamMessageEvent> fallbackStream(UUID sessionId, String reason) {
        log.info("LLM 降级触发: sessionId={}, reason={}", sessionId, reason);
        StreamMessageEvent tokenEvt = new StreamMessageEvent("token", FALLBACK_MESSAGE, null);
        StreamMessageEvent doneEvt = new StreamMessageEvent("done", null, null);
        return Flux.just(tokenEvt, doneEvt);
    }
}
