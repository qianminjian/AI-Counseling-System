package com.mindsafe.ai.safety;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OutputReviewService（Layer2 异步审查）单元测试
 * <p>
 * 覆盖：SAF-002 决策 JSON 解析（含 markdown 代码围栏容错）、模板未加载时的空操作保护。
 */
class OutputReviewServiceTest {

    private OutputReviewService service;

    @BeforeEach
    void setUp() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(mock(ChatClient.class));
        service = new OutputReviewService(builder, Runnable::run, mock(OutputSafetyReporter.class));
    }

    @Test
    @DisplayName("纯 JSON → 正确解析 decision")
    void should_parse_plain_json() {
        assertThat(service.parseDecision("{\"decision\":\"pass\",\"violations\":[]}")).isEqualTo("pass");
        assertThat(service.parseDecision("{\"decision\":\"block\",\"violations\":[]}")).isEqualTo("block");
        assertThat(service.parseDecision("{\"decision\":\"escalate\"}")).isEqualTo("escalate");
    }

    @Test
    @DisplayName("markdown 代码围栏包裹的 JSON → 剥离后解析")
    void should_parse_json_in_code_fence() {
        String raw = "```json\n{\"decision\":\"rewrite\",\"rewritten_reply\":\"我听到你……\"}\n```";
        assertThat(service.parseDecision(raw)).isEqualTo("rewrite");
    }

    @Test
    @DisplayName("无语言标记的代码围栏")
    void should_parse_json_in_plain_fence() {
        String raw = "```\n{\"decision\":\"block\"}\n```";
        assertThat(service.parseDecision(raw)).isEqualTo("block");
    }

    @Test
    @DisplayName("非 JSON 响应 → 返回 null（视为未决，不上报）")
    void should_return_null_for_garbage() {
        assertThat(service.parseDecision("这不是 JSON")).isNull();
        assertThat(service.parseDecision("")).isNull();
    }

    @Test
    @DisplayName("缺少 decision 字段 → 返回 null")
    void should_return_null_when_decision_missing() {
        assertThat(service.parseDecision("{\"violations\":[]}")).isNull();
    }

    @Test
    @DisplayName("模板未加载时 reviewAsync 空操作不抛异常")
    void should_noop_when_template_not_loaded() {
        assertThatCode(() -> service.reviewAsync(UUID.randomUUID(), "任意回复", "sad"))
                .doesNotThrowAnyException();
    }
}
