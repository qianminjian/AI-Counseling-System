package com.mindsafe.ai.chat;

import com.mindsafe.ai.prompt.PromptTemplateService;
import com.mindsafe.common.dto.chat.StreamMessageEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LlmStreamEnhancer 单元测试（13/20 篇审计补齐：PERF-001 超时降级 + PERF-002 重试路径）
 * 覆盖：正常透传、瞬时失败重试成功、重试耗尽降级、首 token 超时降级、整体流超时降级、旧签名兼容
 */
class LlmStreamEnhancerTest {

    private final UUID sessionId = UUID.randomUUID();

    private LlmStreamEnhancer enhancer(long firstTokenMs, long overallMs, int retryMax, long backoffMs) {
        return new LlmStreamEnhancer(firstTokenMs, overallMs, retryMax, backoffMs,
                new SimpleMeterRegistry(), new PromptTemplateService());
    }

    private StreamMessageEvent token(String content) {
        return new StreamMessageEvent("token", content, null);
    }

    @Test
    @DisplayName("正常流透传：token + done 事件原样输出")
    void normalStreamPassThrough() {
        LlmStreamEnhancer e = enhancer(3000, 60000, 1, 10);

        List<StreamMessageEvent> events = e.enhance(
                () -> Flux.just(token("你好"), new StreamMessageEvent("done", null, null)),
                sessionId).collectList().block(Duration.ofSeconds(5));

        assertThat(events).hasSize(2);
        assertThat(events.get(0).type()).isEqualTo("token");
        assertThat(events.get(0).content()).isEqualTo("你好");
        assertThat(events.get(1).type()).isEqualTo("done");
    }

    @Test
    @DisplayName("瞬时失败重试成功：第一次失败、第二次成功 → 工厂被调用 2 次")
    void retrySucceedsOnSecondAttempt() {
        LlmStreamEnhancer e = enhancer(3000, 60000, 1, 10);
        AtomicInteger calls = new AtomicInteger(0);

        List<StreamMessageEvent> events = e.enhance(() -> {
            if (calls.incrementAndGet() == 1) {
                return Flux.error(new RuntimeException("connection reset"));
            }
            return Flux.just(token("恢复了"), new StreamMessageEvent("done", null, null));
        }, sessionId).collectList().block(Duration.ofSeconds(5));

        assertThat(calls.get()).isEqualTo(2);
        assertThat(events).hasSize(2);
        assertThat(events.get(0).content()).isEqualTo("恢复了");
    }

    @Test
    @DisplayName("重试耗尽 → 降级安全话术（非超时错误）")
    void retryExhaustedFallsBack() {
        LlmStreamEnhancer e = enhancer(3000, 60000, 1, 10);
        AtomicInteger calls = new AtomicInteger(0);

        List<StreamMessageEvent> events = e.enhance(() -> {
            calls.incrementAndGet();
            return Flux.error(new RuntimeException("persistent failure"));
        }, sessionId).collectList().block(Duration.ofSeconds(5));

        assertThat(calls.get()).isEqualTo(2); // 首次 + 1 次重试
        assertThat(events).hasSize(2);
        assertThat(events.get(0).type()).isEqualTo("token");
        assertThat(events.get(0).content()).contains("深呼吸");
        assertThat(events.get(1).type()).isEqualTo("done");
    }

    @Test
    @DisplayName("首 token 超时 → 不重试，直接降级话术")
    void firstTokenTimeoutFallsBackWithoutRetry() {
        LlmStreamEnhancer e = enhancer(80, 60000, 1, 10);
        AtomicInteger calls = new AtomicInteger(0);

        List<StreamMessageEvent> events = e.enhance(() -> {
            calls.incrementAndGet();
            return Flux.<StreamMessageEvent>never(); // 永远等不到首 token
        }, sessionId).collectList().block(Duration.ofSeconds(5));

        assertThat(calls.get()).isEqualTo(1); // 超时不触发重试
        assertThat(events).hasSize(2);
        assertThat(events.get(0).content()).contains("深呼吸");
    }

    @Test
    @DisplayName("整体流超时（首 token 后停滞）→ 降级话术")
    void overallTimeoutFallsBack() {
        LlmStreamEnhancer e = enhancer(3000, 100, 0, 10);

        List<StreamMessageEvent> events = e.enhance(
                () -> Flux.concat(Flux.just(token("开头")), Flux.never()),
                sessionId).collectList().block(Duration.ofSeconds(5));

        // 降级流替换后续输出：包含降级话术 token + done
        assertThat(events).isNotEmpty();
        assertThat(events.stream().filter(evt -> "token".equals(evt.type()))
                .anyMatch(evt -> evt.content().contains("深呼吸"))).isTrue();
    }

    @Test
    @DisplayName("降级话术不泄露模板元标题（BUG-S-002）：不得出现 # 标题行与「降级话术」元描述")
    void fallbackMessageStripsMarkdownHeading() {
        LlmStreamEnhancer e = enhancer(3000, 60000, 1, 10);

        List<StreamMessageEvent> events = e.enhance(
                () -> Flux.error(new RuntimeException("fallback path")),
                sessionId).collectList().block(Duration.ofSeconds(5));

        String fallbackText = events.get(0).content();
        // 元描述（维护者视角）不得下发给儿童
        assertThat(fallbackText).doesNotContain("#");
        assertThat(fallbackText).doesNotContain("降级话术");
        // 正文必须保留且从话术本体开头
        assertThat(fallbackText).startsWith("波波现在有点忙不过来");
        assertThat(fallbackText).contains("深呼吸");
    }

    @Test
    @DisplayName("旧签名 enhance(Flux) 兼容：仅超时保护，行为一致")
    void legacyFluxSignature() {
        LlmStreamEnhancer e = enhancer(3000, 60000, 1, 10);

        List<StreamMessageEvent> events = e.enhance(
                Flux.just(token("旧接口"), new StreamMessageEvent("done", null, null)),
                sessionId).collectList().block(Duration.ofSeconds(5));

        assertThat(events).hasSize(2);
        assertThat(events.get(0).content()).isEqualTo("旧接口");
    }
}
