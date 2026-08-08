package com.mindsafe.ai.chat;

import com.mindsafe.ai.prompt.PromptTemplateService;
import com.mindsafe.common.dto.chat.StreamMessageEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * LLM 流式调用性能增强（PERF-001 + PERF-002 重试）
 * <p>
 * 功能：
 * 1. 首 token 超时检测（默认 3s，目标 < 1s）
 * 2. 整体流超时（默认 60s）
 * 3. 超时降级：返回安全话术（不让学生等待无响应）
 * 4. 首 token 延迟指标日志（供 Prometheus 采集）
 * 5. 瞬时失败自动重试 1 次（2s 退避），重试仍失败才走降级话术
 */
@Component
public class LlmStreamEnhancer {

    private static final Logger log = LoggerFactory.getLogger(LlmStreamEnhancer.class);

    private final Duration firstTokenTimeout;
    private final Duration overallTimeout;
    private final int maxRetries;
    private final Duration retryBackoff;

    // ---- Micrometer 指标 ----
    private final Counter retryCounter;
    private final Counter timeoutCounter;
    private final Counter fallbackCounter;
    private final Timer firstTokenTimer;

    /** 降级话术模板 classpath 路径（B4：文案下沉 prompts/，改文案不改代码） */
    private static final String FALLBACK_TEMPLATE_PATH =
            "prompts/fallback/llm_unavailable_zh-CN_v1.0.0.md";

    /**
     * 降级安全话术（面向小学生，温和不突兀）。
     * B4：文案下沉 prompts/fallback/ 模板（启动时加载，配置缺失即启动失败——降级话术不可静默丢失）。
     */
    private final String fallbackMessage;

    public LlmStreamEnhancer(
            @Value("${mindsafe.llm.first-token-timeout-ms:15000}") long firstTokenMs,
            @Value("${mindsafe.llm.overall-timeout-ms:60000}") long overallMs,
            @Value("${mindsafe.llm.retry-max:1}") int retryMax,
            @Value("${mindsafe.llm.retry-backoff-ms:2000}") long retryBackoffMs,
            MeterRegistry meterRegistry,
            PromptTemplateService promptTemplateService) {
        this.firstTokenTimeout = Duration.ofMillis(firstTokenMs);
        this.overallTimeout = Duration.ofMillis(overallMs);
        this.maxRetries = retryMax;
        this.retryBackoff = Duration.ofMillis(retryBackoffMs);
        this.fallbackMessage = promptTemplateService.getTemplate(FALLBACK_TEMPLATE_PATH);

        this.retryCounter = Counter.builder("mindsafe.llm.retry")
                .description("LLM 流式调用重试次数")
                .register(meterRegistry);
        this.timeoutCounter = Counter.builder("mindsafe.llm.timeout")
                .description("LLM 超时降级次数")
                .register(meterRegistry);
        this.fallbackCounter = Counter.builder("mindsafe.llm.fallback")
                .description("LLM 降级话术触发次数")
                .register(meterRegistry);
        this.firstTokenTimer = Timer.builder("mindsafe.llm.first_token_latency")
                .description("LLM 首 token 延迟")
                .publishPercentiles(0.5, 0.9, 0.99)
                .register(meterRegistry);

        log.info("LLM 性能增强初始化: firstTokenTimeout={}ms, overallTimeout={}ms, retryMax={}, retryBackoff={}ms",
                firstTokenMs, overallMs, retryMax, retryBackoffMs);
    }

    /**
     * 包装原始 LLM 流，添加重试 + 超时保护 + 降级 + 指标。
     * <p>
     * 使用 Supplier 确保每次重试创建全新的 LLM 调用（cold Flux 重订阅）。
     *
     * @param streamFactory 流工厂（每次订阅/重试时调用，创建新的 LLM 请求）
     * @param sessionId     会话 ID（日志关联）
     * @return 增强后的事件流
     */
    public Flux<StreamMessageEvent> enhance(Supplier<Flux<StreamMessageEvent>> streamFactory, UUID sessionId) {
        AtomicInteger attemptCount = new AtomicInteger(0);

        return Flux.defer(() -> {
                    int attempt = attemptCount.incrementAndGet();
                    AtomicLong startTime = new AtomicLong(System.currentTimeMillis());
                    AtomicBoolean firstTokenReceived = new AtomicBoolean(false);

                    if (attempt > 1) {
                        log.info("LLM 重试第 {} 次: sessionId={}", attempt - 1, sessionId);
                    }

                    return streamFactory.get()
                            // 首 token 延迟监控
                            .doOnNext(evt -> {
                                if ("token".equals(evt.type()) && firstTokenReceived.compareAndSet(false, true)) {
                                    long latency = System.currentTimeMillis() - startTime.get();
                                    firstTokenTimer.record(Duration.ofMillis(latency));
                                    log.info("LLM 首 token 延迟: sessionId={}, latency={}ms, attempt={}",
                                            sessionId, latency, attempt);
                                    if (latency > 1000) {
                                        log.warn("LLM 首 token 超过 1s 目标: sessionId={}, latency={}ms", sessionId, latency);
                                    }
                                }
                            })
                            // 首 token 超时：如果第一个元素超过阈值，触发错误（由 retryWhen 捕获）
                            .timeout(firstTokenTimeout);
                })
                // 瞬时失败重试：网络抖动/限流/连接重置等，退避后重试
                .retryWhen(Retry.backoff(maxRetries, retryBackoff)
                        .maxBackoff(Duration.ofSeconds(5))
                        .filter(e -> !(e instanceof java.util.concurrent.TimeoutException))
                        .doBeforeRetry(signal -> {
                            retryCounter.increment();
                            log.warn("LLM 流异常，准备重试: sessionId={}, attempt={}, error={}",
                                    sessionId, signal.totalRetries() + 1, signal.failure().getMessage());
                        })
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                // 首 token 超时降级（重试不覆盖超时——超时说明模型本身慢，重试无意义）
                .onErrorResume(java.util.concurrent.TimeoutException.class, e -> {
                    timeoutCounter.increment();
                    fallbackCounter.increment();
                    log.warn("LLM 首 token 超时降级: sessionId={}, timeout={}ms", sessionId, firstTokenTimeout.toMillis());
                    return fallbackStream(sessionId, "first_token_timeout");
                })
                // 整体流超时
                .timeout(overallTimeout)
                .onErrorResume(java.util.concurrent.TimeoutException.class, e -> {
                    timeoutCounter.increment();
                    fallbackCounter.increment();
                    log.warn("LLM 整体流超时降级: sessionId={}, timeout={}ms", sessionId, overallTimeout.toMillis());
                    return fallbackStream(sessionId, "overall_timeout");
                })
                // 重试耗尽后的最终降级
                .onErrorResume(e -> {
                    if (e instanceof java.util.concurrent.TimeoutException) {
                        return Flux.empty();
                    }
                    fallbackCounter.increment();
                    log.error("LLM 流异常降级（重试已耗尽）: sessionId={}, attempts={}", sessionId, attemptCount.get(), e);
                    return fallbackStream(sessionId, "error:" + e.getClass().getSimpleName());
                });
    }

    /**
     * 兼容旧签名：直接传入 Flux（无法重试，仅做超时保护）。
     * 新代码应使用 Supplier 版本以启用重试。
     */
    public Flux<StreamMessageEvent> enhance(Flux<StreamMessageEvent> rawStream, UUID sessionId) {
        return enhance(() -> rawStream, sessionId);
    }

    /**
     * 降级流：返回安全话术 token + done 事件
     */
    private Flux<StreamMessageEvent> fallbackStream(UUID sessionId, String reason) {
        log.info("LLM 降级触发: sessionId={}, reason={}", sessionId, reason);
        StreamMessageEvent tokenEvt = new StreamMessageEvent("token", fallbackMessage, null);
        StreamMessageEvent doneEvt = new StreamMessageEvent("done", null, null);
        return Flux.just(tokenEvt, doneEvt);
    }
}
