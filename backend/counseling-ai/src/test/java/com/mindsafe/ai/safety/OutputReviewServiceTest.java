package com.mindsafe.ai.safety;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OutputReviewService（Layer2 异步审查）单元测试
 * <p>
 * 覆盖：SAF-002 决策 JSON 解析（含 markdown 代码围栏容错）、模板加载、
 * reviewAsync 空操作保护、review 主流程与 SAFE-202 四决策处置分发。
 */
class OutputReviewServiceTest {

    private OutputReviewService service;
    private ChatClient reviewClient;
    private OutputSafetyReporter reporter;

    @BeforeEach
    void setUp() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        reviewClient = mock(ChatClient.class, Answers.RETURNS_DEEP_STUBS);
        reporter = mock(OutputSafetyReporter.class);
        when(builder.build()).thenReturn(reviewClient);
        service = new OutputReviewService(builder, Runnable::run, reporter);
    }

    /** 设置已加载模板 + 打桩 LLM 审查返回 */
    private void stubLlmReply(String raw) {
        ReflectionTestUtils.setField(service, "promptTemplate", "审查：{candidate_reply} / {context}");
        when(reviewClient.prompt().user(anyString()).call().content()).thenReturn(raw);
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

    @Nested
    @DisplayName("模板加载与 reviewAsync 守卫")
    class Guards {

        @Test
        @DisplayName("loadTemplate：classpath 模板加载成功（SAF-002 生效前提）")
        void templateLoaded() {
            service.loadTemplate();

            assertThat((String) ReflectionTestUtils.getField(service, "promptTemplate")).isNotBlank();
        }

        @Test
        @DisplayName("空回复 → 不触发 LLM 审查")
        void blankReply_noReview() {
            ReflectionTestUtils.setField(service, "promptTemplate", "模板");

            service.reviewAsync(UUID.randomUUID(), "  ", "sad");

            verify(reviewClient, never()).prompt();
            verify(reporter, never()).reportLayer2Violation(org.mockito.ArgumentMatchers.any(), anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("review 主流程 + SAFE-202 四决策处置")
    class ReviewDispatch {

        private final UUID sessionId = UUID.randomUUID();

        @Test
        @DisplayName("pass → 放行，不上报不召回")
        void pass_noAction() {
            stubLlmReply("{\"decision\":\"pass\"}");

            service.review(sessionId, "正常回复", "neutral");

            verify(reporter, never()).reportLayer2Violation(org.mockito.ArgumentMatchers.any(), anyString(), anyString());
            verify(reporter, never()).applyLayer2Recall(org.mockito.ArgumentMatchers.any(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("rewrite + 有改写版 → 用 LLM 改写版召回替换")
        void rewrite_withRewritten() {
            stubLlmReply("{\"decision\":\"rewrite\",\"rewritten_reply\":\"改写后的适龄回复\"}");

            service.review(sessionId, "偏成人化回复", "sad");

            verify(reporter).applyLayer2Recall(eq(sessionId), eq("rewrite"), eq("改写后的适龄回复"), anyString());
        }

        @Test
        @DisplayName("rewrite 但缺改写版 → 退化为仅留痕")
        void rewrite_missingRewritten_degrades() {
            stubLlmReply("{\"decision\":\"rewrite\"}");

            service.review(sessionId, "回复", "sad");

            verify(reporter).reportLayer2Violation(eq(sessionId), eq("rewrite"), anyString());
            verify(reporter, never()).applyLayer2Recall(org.mockito.ArgumentMatchers.any(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("block → 预审核 BLOCK_RECALL 话术召回替换")
        void block_usesPreApprovedPhrase() {
            stubLlmReply("{\"decision\":\"block\"}");

            service.review(sessionId, "违规回复", "sad");

            verify(reporter).applyLayer2Recall(eq(sessionId), eq("block"), eq(RecallPhrases.BLOCK_RECALL), anyString());
        }

        @Test
        @DisplayName("escalate → 预审核 ESCALATE_RECALL 安全处置话术召回替换")
        void escalate_usesPreApprovedPhrase() {
            stubLlmReply("{\"decision\":\"escalate\",\"escalation_reason\":\"高风险未处置\"}");

            service.review(sessionId, "回复", "crisis");

            verify(reporter).applyLayer2Recall(eq(sessionId), eq("escalate"), eq(RecallPhrases.ESCALATE_RECALL), anyString());
        }

        @Test
        @DisplayName("未知决策 → 兜底仅留痕")
        void unknownDecision_reportOnly() {
            stubLlmReply("{\"decision\":\"weird\"}");

            service.review(sessionId, "回复", "sad");

            verify(reporter).reportLayer2Violation(eq(sessionId), eq("weird"), anyString());
        }

        @Test
        @DisplayName("LLM 返回空/坏 JSON/缺 decision → 视为未决，不上报")
        void undecidable_noReport() {
            stubLlmReply("");
            service.review(sessionId, "回复", "sad");

            stubLlmReply("这不是 JSON");
            service.review(sessionId, "回复", "sad");

            stubLlmReply("{\"violations\":[]}");
            service.review(sessionId, "回复", "sad");

            verify(reporter, never()).reportLayer2Violation(org.mockito.ArgumentMatchers.any(), anyString(), anyString());
            verify(reporter, never()).applyLayer2Recall(org.mockito.ArgumentMatchers.any(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("LLM 调用异常 → 吞掉，不影响主流程")
        void llmError_swallowed() {
            ReflectionTestUtils.setField(service, "promptTemplate", "模板：{candidate_reply} {context}");
            when(reviewClient.prompt().user(anyString()).call().content())
                    .thenThrow(new RuntimeException("llm down"));

            assertThatCode(() -> service.review(sessionId, "回复", "sad")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("处置（reporter）异常 → 吞掉，不影响主流程")
        void dispatchError_swallowed() {
            stubLlmReply("{\"decision\":\"block\"}");
            org.mockito.Mockito.doThrow(new RuntimeException("redis down"))
                    .when(reporter).applyLayer2Recall(org.mockito.ArgumentMatchers.any(), anyString(), anyString(), anyString());

            assertThatCode(() -> service.review(sessionId, "回复", "sad")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("reviewAsync 模板已加载 + 同步执行器 → 实际执行审查链路")
        void reviewAsync_executes() {
            stubLlmReply("{\"decision\":\"pass\"}");

            service.reviewAsync(sessionId, "回复内容", "neutral");

            // 打桩时深桩链也会记录一次 prompt()，故用 atLeastOnce
            verify(reviewClient, atLeastOnce()).prompt();
        }
    }
}
