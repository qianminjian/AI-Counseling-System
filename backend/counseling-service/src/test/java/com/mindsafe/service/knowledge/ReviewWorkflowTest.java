package com.mindsafe.service.knowledge;

import com.mindsafe.service.knowledge.ReviewGateValidator.GateResult;
import com.mindsafe.service.knowledge.ReviewWorkflowStateMachine.ReviewStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * KB-102 审核工作流状态机 + 门禁 + 元数据 单元测试
 */
class ReviewWorkflowTest {

    private final ReviewWorkflowStateMachine sm = new ReviewWorkflowStateMachine();
    private final ReviewGateValidator gate = new ReviewGateValidator();

    // ===== 状态机测试 =====

    @Nested
    @DisplayName("状态机转移")
    class StateMachineTests {

        @Test
        @DisplayName("draft → in_review 合法")
        void draftToInReview() {
            assertThat(sm.canTransition(ReviewStatus.DRAFT, ReviewStatus.IN_REVIEW)).isTrue();
            assertThat(sm.transition(ReviewStatus.DRAFT, ReviewStatus.IN_REVIEW))
                    .isEqualTo(ReviewStatus.IN_REVIEW);
        }

        @Test
        @DisplayName("in_review → published 合法")
        void inReviewToPublished() {
            assertThat(sm.canTransition(ReviewStatus.IN_REVIEW, ReviewStatus.PUBLISHED)).isTrue();
        }

        @Test
        @DisplayName("in_review → draft（驳回）合法")
        void inReviewRejectToDraft() {
            assertThat(sm.canTransition(ReviewStatus.IN_REVIEW, ReviewStatus.DRAFT)).isTrue();
        }

        @Test
        @DisplayName("published → deprecated 合法")
        void publishedToDeprecated() {
            assertThat(sm.canTransition(ReviewStatus.PUBLISHED, ReviewStatus.DEPRECATED)).isTrue();
        }

        @Test
        @DisplayName("draft → published 非法（跳级）")
        void draftToPublished_illegal() {
            assertThat(sm.canTransition(ReviewStatus.DRAFT, ReviewStatus.PUBLISHED)).isFalse();
            assertThatThrownBy(() -> sm.transition(ReviewStatus.DRAFT, ReviewStatus.PUBLISHED))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("非法状态转移");
        }

        @Test
        @DisplayName("deprecated → 任何状态 非法（终态）")
        void deprecated_isTerminal() {
            assertThat(sm.canTransition(ReviewStatus.DEPRECATED, ReviewStatus.DRAFT)).isFalse();
            assertThat(sm.canTransition(ReviewStatus.DEPRECATED, ReviewStatus.PUBLISHED)).isFalse();
            assertThat(sm.allowedTargets(ReviewStatus.DEPRECATED)).isEmpty();
        }

        @Test
        @DisplayName("published → draft 非法（需先 deprecated 再重走）")
        void publishedToDraft_illegal() {
            assertThat(sm.canTransition(ReviewStatus.PUBLISHED, ReviewStatus.DRAFT)).isFalse();
        }

        @Test
        @DisplayName("null 状态 → 非法")
        void nullState_illegal() {
            assertThat(sm.canTransition(null, ReviewStatus.DRAFT)).isFalse();
            assertThat(sm.canTransition(ReviewStatus.DRAFT, null)).isFalse();
        }

        @Test
        @DisplayName("isSearchable 仅 published 为 true")
        void searchable_onlyPublished() {
            assertThat(sm.isSearchable(ReviewStatus.PUBLISHED)).isTrue();
            assertThat(sm.isSearchable(ReviewStatus.DRAFT)).isFalse();
            assertThat(sm.isSearchable(ReviewStatus.IN_REVIEW)).isFalse();
            assertThat(sm.isSearchable(ReviewStatus.DEPRECATED)).isFalse();
        }

        @Test
        @DisplayName("fromDbStatus 兼容 'active' → PUBLISHED")
        void fromDbStatus_active() {
            assertThat(ReviewWorkflowStateMachine.fromDbStatus("active")).isEqualTo(ReviewStatus.PUBLISHED);
            assertThat(ReviewWorkflowStateMachine.fromDbStatus("published")).isEqualTo(ReviewStatus.PUBLISHED);
            assertThat(ReviewWorkflowStateMachine.fromDbStatus("in_review")).isEqualTo(ReviewStatus.IN_REVIEW);
            assertThat(ReviewWorkflowStateMachine.fromDbStatus("deprecated")).isEqualTo(ReviewStatus.DEPRECATED);
            assertThat(ReviewWorkflowStateMachine.fromDbStatus(null)).isEqualTo(ReviewStatus.DRAFT);
            assertThat(ReviewWorkflowStateMachine.fromDbStatus("unknown")).isEqualTo(ReviewStatus.DRAFT);
        }
    }

    // ===== 门禁测试 =====

    @Nested
    @DisplayName("审核门禁")
    class GateTests {

        @Test
        @DisplayName("发布门禁全通过")
        void publish_allPass() {
            KnowledgeMetadata meta = new KnowledgeMetadata(
                    "doc-1", "cbt_technique", "mid", "clinical_authored", "high",
                    ReviewStatus.IN_REVIEW, 1, "dr_wang", Instant.now(), false);
            GateResult result = gate.validateForPublish(meta);
            assertThat(result.passed()).isTrue();
            assertThat(result.violations()).isEmpty();
        }

        @Test
        @DisplayName("发布门禁：缺循证等级 → 失败")
        void publish_missingEvidence() {
            KnowledgeMetadata meta = new KnowledgeMetadata(
                    "doc-1", "cbt_technique", "mid", "clinical_authored", null,
                    ReviewStatus.IN_REVIEW, 1, "dr_wang", Instant.now(), false);
            GateResult result = gate.validateForPublish(meta);
            assertThat(result.passed()).isFalse();
            assertThat(result.violations()).anyMatch(v -> v.contains("GATE_PROFESSIONAL"));
        }

        @Test
        @DisplayName("发布门禁：缺审核人 → 失败")
        void publish_missingReviewer() {
            KnowledgeMetadata meta = new KnowledgeMetadata(
                    "doc-1", "emotion_regulation", "all", "official", "medium",
                    ReviewStatus.IN_REVIEW, 1, null, null, false);
            GateResult result = gate.validateForPublish(meta);
            assertThat(result.passed()).isFalse();
            assertThat(result.violations()).anyMatch(v -> v.contains("GATE_TRACEABILITY"));
        }

        @Test
        @DisplayName("发布门禁：crisis_intervention 未标 safety_sensitive → 红队失败")
        void publish_crisis_notSensitive() {
            KnowledgeMetadata meta = new KnowledgeMetadata(
                    "doc-1", "crisis_intervention", "high", "official", "high",
                    ReviewStatus.IN_REVIEW, 1, "dr_li", Instant.now(), false);
            GateResult result = gate.validateForPublish(meta);
            assertThat(result.passed()).isFalse();
            assertThat(result.violations()).anyMatch(v -> v.contains("GATE_REDTEAM"));
        }

        @Test
        @DisplayName("发布门禁：crisis + safety_sensitive + 缺 source_type → 合规失败")
        void publish_crisis_sensitive_noSource() {
            KnowledgeMetadata meta = new KnowledgeMetadata(
                    "doc-1", "crisis_intervention", "high", null, "high",
                    ReviewStatus.IN_REVIEW, 1, "dr_li", Instant.now(), true);
            GateResult result = gate.validateForPublish(meta);
            assertThat(result.passed()).isFalse();
            assertThat(result.violations()).anyMatch(v -> v.contains("GATE_COMPLIANCE"));
        }

        @Test
        @DisplayName("提交审核门禁：缺分类 → 失败")
        void review_missingCategory() {
            KnowledgeMetadata meta = new KnowledgeMetadata(
                    "doc-1", null, "mid", null, null,
                    ReviewStatus.DRAFT, 0, null, null, false);
            GateResult result = gate.validateForReview(meta);
            assertThat(result.passed()).isFalse();
            assertThat(result.violations()).anyMatch(v -> v.contains("GATE_INTAKE"));
        }

        @Test
        @DisplayName("组合校验：非法转移直接失败")
        void transition_illegalState() {
            KnowledgeMetadata meta = KnowledgeMetadata.draft("doc-1", "cbt_technique", "mid");
            GateResult result = gate.validateTransition(sm, ReviewStatus.DRAFT, ReviewStatus.PUBLISHED, meta);
            assertThat(result.passed()).isFalse();
            assertThat(result.violations()).anyMatch(v -> v.contains("STATE_MACHINE"));
        }

        @Test
        @DisplayName("组合校验：deprecated 无额外门禁")
        void transition_deprecated_noGate() {
            KnowledgeMetadata meta = new KnowledgeMetadata(
                    "doc-1", "cbt_technique", "mid", null, null,
                    ReviewStatus.PUBLISHED, 1, null, null, false);
            GateResult result = gate.validateTransition(sm, ReviewStatus.PUBLISHED, ReviewStatus.DEPRECATED, meta);
            assertThat(result.passed()).isTrue();
        }
    }

    // ===== 元数据测试 =====

    @Nested
    @DisplayName("知识元数据")
    class MetadataTests {

        @Test
        @DisplayName("draft 工厂：crisis_intervention 自动 safety_sensitive=true")
        void draftFactory_crisisSensitive() {
            KnowledgeMetadata crisis = KnowledgeMetadata.draft("d", "crisis_intervention", "all");
            assertThat(crisis.safetySensitive()).isTrue();
            assertThat(crisis.reviewStatus()).isEqualTo(ReviewStatus.DRAFT);

            KnowledgeMetadata normal = KnowledgeMetadata.draft("d", "cbt_technique", "low");
            assertThat(normal.safetySensitive()).isFalse();
        }
    }
}
