package com.mindsafe.ai.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.DefaultChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ResilientChatModel 降级装饰器测试（BUG-LLM-02：降级转发必须替换 model，否则备用供应商
 * unknown model 拒绝——生产实证 MiniMax 400 (2013)）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ResilientChatModel 降级装饰器")
class ResilientChatModelTest {

    private static final String PRIMARY_MODEL = "deepseek-v4-pro";
    private static final String FALLBACK_MODEL = "MiniMax-M3";

    @Mock private ChatModel primary;
    @Mock private ChatModel fallback;

    private ResilientChatModel model;

    @BeforeEach
    void setUp() {
        model = new ResilientChatModel(primary, fallback, PRIMARY_MODEL, FALLBACK_MODEL,
                new SimpleMeterRegistry());
    }

    private Prompt promptWithModel(String model) {
        DefaultChatOptions options = new DefaultChatOptions();
        options.setModel(model);
        return new Prompt(List.of(new UserMessage("你好")), options);
    }

    /** ChatResponse.builder().build() 会因 results 为 null 抛 NPE（List.copyOf），须显式提供 generations */
    private ChatResponse okResponse() {
        return ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("ok"))))
                .build();
    }

    @Test
    @DisplayName("BUG-LLM-02: 主模型失败降级 → fallback 收到 model 已替换为备用名的 Prompt")
    void fallbackReceivesReplacedModel() {
        Prompt original = promptWithModel(PRIMARY_MODEL);
        when(primary.call(any(Prompt.class))).thenThrow(new RuntimeException("timeout"));
        when(fallback.call(any(Prompt.class))).thenReturn(okResponse());

        model.call(original);

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(fallback).call(captor.capture());
        assertThat(captor.getValue().getOptions().getModel()).isEqualTo(FALLBACK_MODEL);
    }

    @Test
    @DisplayName("主模型调用成功 → fallback 不被调用，原 Prompt 不变")
    void primarySuccess_noFallback() {
        Prompt original = promptWithModel(PRIMARY_MODEL);
        when(primary.call(any(Prompt.class))).thenReturn(okResponse());

        model.call(original);

        verify(fallback, never()).call(any(Prompt.class));
    }

    @Test
    @DisplayName("BUG-LLM-02: 流式主模型失败降级 → fallback 收到替换后的 model")
    void fallbackStreamReceivesReplacedModel() {
        Prompt original = promptWithModel(PRIMARY_MODEL);
        when(primary.stream(any(Prompt.class))).thenReturn(Flux.error(new RuntimeException("timeout")));
        when(fallback.stream(any(Prompt.class))).thenReturn(Flux.just(okResponse()));

        model.stream(original).blockLast();

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(fallback).stream(captor.capture());
        assertThat(captor.getValue().getOptions().getModel()).isEqualTo(FALLBACK_MODEL);
    }

    @Test
    @DisplayName("降级时 Prompt 无 options → 原样转发（不 NPE）")
    void fallbackWithNullOptions_forwardsAsIs() {
        Prompt original = new Prompt(List.of(new UserMessage("你好")));
        when(primary.call(any(Prompt.class))).thenThrow(new RuntimeException("timeout"));
        when(fallback.call(any(Prompt.class))).thenReturn(okResponse());

        model.call(original);

        verify(fallback).call(original);
    }
}
