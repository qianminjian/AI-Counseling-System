package com.mindsafe.service.knowledge;

import com.mindsafe.service.audit.AuditLogService;
import com.mindsafe.service.knowledge.ReviewWorkflowStateMachine.ReviewStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EditorialWorkflowService 单测（G-2，design/49 §五 运营侧采编工作流）
 * <p>
 * 契约：
 * - 采编流水线编排：draft → in_review → published/deprecated，每步状态机+门禁组合校验
 * - 门禁不过 → 拒绝转移且不落库，违规项全部透出
 * - 每次合法转移经 audit_logs 留痕（免 schema 变更红线）
 * - 运营报表：内容缺口聚合（接 KB-103 identifyContentGaps）+ 分类覆盖统计
 */
@ExtendWith(MockitoExtension.class)
class EditorialWorkflowServiceTest {

    @Mock
    private KnowledgeBaseService knowledgeBaseService;

    @Mock
    private AuditLogService auditLogService;

    private final ReviewWorkflowStateMachine stateMachine = new ReviewWorkflowStateMachine();
    private final ReviewGateValidator gateValidator = new ReviewGateValidator();
    private final HybridRetrievalService retrievalService = new HybridRetrievalService();

    private EditorialWorkflowService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID operatorId = UUID.randomUUID();
    private final UUID docId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new EditorialWorkflowService(knowledgeBaseService, stateMachine,
                gateValidator, retrievalService, auditLogService);
    }

    /** 合法元数据：分类+年级段+循证等级+审核人 */
    private EditorialWorkflowService.EditorialRequest validRequest() {
        return new EditorialWorkflowService.EditorialRequest(
                "coping_tools", "mid", "clinical_authored", "高", "临床组-王老师");
    }

    @Nested
    @DisplayName("采编流水线编排")
    class Pipeline {

        @Test
        @DisplayName("提交审核：draft → in_review（门禁过）落库+审计")
        void submitForReview_success() {
            when(knowledgeBaseService.findDocumentStatus(docId)).thenReturn("draft");

            EditorialWorkflowService.TransitionResult result = service.submitForReview(
                    tenantId, operatorId, docId, validRequest());

            assertTrue(result.success());
            assertEquals("in_review", result.toStatus());
            verify(knowledgeBaseService).transitionReviewStatus(docId, "in_review",
                    "mid", "clinical_authored", "高", null);
            verify(auditLogService).log(eq(tenantId), eq(operatorId),
                    eq("EDITORIAL_SUBMIT_REVIEW"), eq("knowledge_document"), eq(docId), anyString());
        }

        @Test
        @DisplayName("提交审核缺年级段 → 门禁拒绝，不落库不审计")
        void submitForReview_missingGradeBand_rejected() {
            when(knowledgeBaseService.findDocumentStatus(docId)).thenReturn("draft");

            EditorialWorkflowService.TransitionResult result = service.submitForReview(
                    tenantId, operatorId, docId,
                    new EditorialWorkflowService.EditorialRequest(
                            "coping_tools", null, null, null, null));

            assertFalse(result.success());
            assertTrue(String.join(";", result.violations()).contains("grade_band"));
            verify(knowledgeBaseService, never()).transitionReviewStatus(
                    any(), anyString(), any(), any(), any(), any());
            verify(auditLogService, never()).log(any(), any(), anyString(), anyString(), any(), any());
        }

        @Test
        @DisplayName("发布：in_review → published（四门禁全过）")
        void publish_success() {
            when(knowledgeBaseService.findDocumentStatus(docId)).thenReturn("in_review");

            EditorialWorkflowService.TransitionResult result = service.publish(
                    tenantId, operatorId, docId, validRequest());

            assertTrue(result.success());
            assertEquals("published", result.toStatus());
            assertTrue(result.searchable());
            verify(knowledgeBaseService).transitionReviewStatus(docId, "published",
                    "mid", "clinical_authored", "高", "临床组-王老师");
            verify(auditLogService).log(eq(tenantId), eq(operatorId),
                    eq("EDITORIAL_PUBLISH"), eq("knowledge_document"), eq(docId), anyString());
        }

        @Test
        @DisplayName("发布缺审核人 → 版本留痕门禁拒绝")
        void publish_missingReviewer_rejected() {
            when(knowledgeBaseService.findDocumentStatus(docId)).thenReturn("in_review");

            EditorialWorkflowService.TransitionResult result = service.publish(
                    tenantId, operatorId, docId,
                    new EditorialWorkflowService.EditorialRequest(
                            "coping_tools", "mid", "clinical_authored", "高", null));

            assertFalse(result.success());
            assertTrue(String.join(";", result.violations()).contains("reviewer"));
        }

        @Test
        @DisplayName("危机类发布：缺 safety_sensitive 判定 → 红队门禁拒绝")
        void publish_crisisWithoutSafetyFlag_rejected() {
            when(knowledgeBaseService.findDocumentStatus(docId)).thenReturn("in_review");

            // crisis_intervention 类由编排层强制 safety_sensitive=true；
            // 缺循证等级 + 缺审核人 → 多重违规全透出
            EditorialWorkflowService.TransitionResult result = service.publish(
                    tenantId, operatorId, docId,
                    new EditorialWorkflowService.EditorialRequest(
                            "crisis_intervention", "all", "official", null, null));

            assertFalse(result.success());
            assertTrue(result.violations().size() >= 2, "应列出全部违规: " + result.violations());
        }

        @Test
        @DisplayName("非法转移：draft 直接发布 → 状态机拒绝")
        void publish_fromDraft_rejected() {
            when(knowledgeBaseService.findDocumentStatus(docId)).thenReturn("draft");

            EditorialWorkflowService.TransitionResult result = service.publish(
                    tenantId, operatorId, docId, validRequest());

            assertFalse(result.success());
            assertTrue(String.join(";", result.violations()).contains("非法转移"));
        }

        @Test
        @DisplayName("deprecated 不可恢复发布（铁律）")
        void publish_fromDeprecated_rejected() {
            when(knowledgeBaseService.findDocumentStatus(docId)).thenReturn("deprecated");

            EditorialWorkflowService.TransitionResult result = service.publish(
                    tenantId, operatorId, docId, validRequest());

            assertFalse(result.success());
        }

        @Test
        @DisplayName("驳回：in_review → draft 允许（无额外门禁）")
        void reject_backToDraft() {
            when(knowledgeBaseService.findDocumentStatus(docId)).thenReturn("in_review");

            EditorialWorkflowService.TransitionResult result = service.reject(
                    tenantId, operatorId, docId, "循证依据不足");

            assertTrue(result.success());
            assertEquals("draft", result.toStatus());
            verify(auditLogService).log(eq(tenantId), eq(operatorId),
                    eq("EDITORIAL_REJECT"), eq("knowledge_document"), eq(docId),
                    ArgumentMatchers.contains("循证依据不足"));
        }

        @Test
        @DisplayName("下线：published → deprecated 允许")
        void deprecate_fromPublished() {
            when(knowledgeBaseService.findDocumentStatus(docId)).thenReturn("published");

            EditorialWorkflowService.TransitionResult result = service.deprecate(
                    tenantId, operatorId, docId, "内容过时");

            assertTrue(result.success());
            assertEquals("deprecated", result.toStatus());
            assertFalse(result.searchable());
        }

        @Test
        @DisplayName("文档不存在 → 失败且不触达状态机")
        void missingDocument_rejected() {
            when(knowledgeBaseService.findDocumentStatus(docId)).thenReturn(null);

            EditorialWorkflowService.TransitionResult result = service.publish(
                    tenantId, operatorId, docId, validRequest());

            assertFalse(result.success());
            assertTrue(String.join(";", result.violations()).contains("不存在"));
        }
    }

    @Nested
    @DisplayName("运营报表（内容缺口 + 覆盖统计）")
    class OperationsReport {

        @Test
        @DisplayName("高频未命中 → 缺口清单按频率降序 + 建议分类")
        void gapReport_sortedByFrequency() {
            List<String> missed = List.of(
                    "怎么放松呼吸", "怎么放松呼吸", "怎么放松呼吸",
                    "和朋友吵架了", "和朋友吵架了", "和朋友吵架了", "和朋友吵架了",
                    "随便一次未命中");

            EditorialWorkflowService.OperationalReport report =
                    service.operationalReport(tenantId, missed, Map.of());

            assertEquals(2, report.contentGaps().size());
            // 朋友类 4 次 > 呼吸类 3 次
            assertEquals("和朋友吵架了", report.contentGaps().get(0).query());
            assertEquals("social_skills", report.contentGaps().get(0).suggestedCategory());
            assertEquals("coping_tools", report.contentGaps().get(1).suggestedCategory());
        }

        @Test
        @DisplayName("覆盖统计：分类 × 状态计数透出")
        void coverageStats() {
            Map<String, List<String>> docs = Map.of(
                    "coping_tools", List.of("published", "published", "draft"),
                    "crisis_intervention", List.of("published"));

            EditorialWorkflowService.OperationalReport report =
                    service.operationalReport(tenantId, List.of(), docs);

            assertEquals(2, report.coverage().size());
            EditorialWorkflowService.CategoryCoverage coping = report.coverage().stream()
                    .filter(c -> "coping_tools".equals(c.category())).findFirst().orElseThrow();
            assertEquals(3, coping.total());
            assertEquals(2, coping.published());
        }

        @Test
        @DisplayName("空输入 → 空报表不抛异常")
        void emptyInputs() {
            EditorialWorkflowService.OperationalReport report =
                    service.operationalReport(tenantId, null, null);

            assertTrue(report.contentGaps().isEmpty());
            assertTrue(report.coverage().isEmpty());
        }
    }
}
