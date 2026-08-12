package com.mindsafe.api.controller;

import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.service.audit.AuditLogService;
import com.mindsafe.service.knowledge.EditorialWorkflowService;
import com.mindsafe.service.knowledge.HybridRetrievalService;
import com.mindsafe.service.knowledge.KnowledgeBaseService;
import com.mindsafe.service.knowledge.KnowledgeCorpusIngestService;
import com.mindsafe.service.knowledge.ReviewGateValidator;
import com.mindsafe.service.knowledge.ReviewWorkflowStateMachine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KnowledgeBaseController 单元测试（P1 覆盖率冲刺：摄入/检索/审核状态机/采编工作流/运营报表）
 */
class KnowledgeBaseControllerTest {

    private KnowledgeBaseService knowledgeBaseService;
    private KnowledgeCorpusIngestService corpusIngestService;
    private AuditLogService auditLogService;
    private ReviewWorkflowStateMachine reviewStateMachine;
    private ReviewGateValidator reviewGateValidator;
    private EditorialWorkflowService editorialWorkflowService;
    private KnowledgeBaseController controller;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID docId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        knowledgeBaseService = mock(KnowledgeBaseService.class);
        corpusIngestService = mock(KnowledgeCorpusIngestService.class);
        auditLogService = mock(AuditLogService.class);
        reviewStateMachine = mock(ReviewWorkflowStateMachine.class);
        reviewGateValidator = mock(ReviewGateValidator.class);
        editorialWorkflowService = mock(EditorialWorkflowService.class);
        controller = new KnowledgeBaseController(knowledgeBaseService, corpusIngestService,
                auditLogService, reviewStateMachine, reviewGateValidator, editorialWorkflowService);
    }

    private Authentication auth() {
        Authentication auth = mock(Authentication.class);
        when(auth.getDetails()).thenReturn(new TenantContext(tenantId, userId, "psych_teacher"));
        return auth;
    }

    // ===== 摄入 =====

    @Test
    @DisplayName("ingest 成功 → docId + 审计")
    void ingest_success() {
        when(knowledgeBaseService.ingestDocument(tenantId, "考试焦虑", "general", "内容", "手册"))
                .thenReturn(docId);

        var resp = controller.ingest(new KnowledgeBaseController.IngestDocumentRequest("考试焦虑", null, "内容", "手册"), auth());

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data().get("docId")).isEqualTo(docId);
        verify(auditLogService).log(tenantId, userId, "KNOWLEDGE_INGEST", "knowledge_document", docId,
                "title=考试焦虑, category=general");
    }

    @Test
    @DisplayName("ingest 缺 title → error")
    void ingest_missingTitle() {
        var resp = controller.ingest(new KnowledgeBaseController.IngestDocumentRequest(null, null, "内容", null), auth());

        assertThat(resp.data().get("error")).isEqualTo("title 和 content 为必填项");
        verify(knowledgeBaseService, never()).ingestDocument(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("ingest content 空白 → error")
    void ingest_blankContent() {
        var resp = controller.ingest(new KnowledgeBaseController.IngestDocumentRequest("标题", null, "  ", null), auth());

        assertThat(resp.data().get("error")).isEqualTo("title 和 content 为必填项");
    }

    @Test
    @DisplayName("ingest category 缺省 general")
    void ingest_defaultCategory() {
        when(knowledgeBaseService.ingestDocument(tenantId, "标题", "general", "内容", null))
                .thenReturn(docId);

        controller.ingest(new KnowledgeBaseController.IngestDocumentRequest("标题", null, "内容", null), auth());

        verify(knowledgeBaseService).ingestDocument(tenantId, "标题", "general", "内容", null);
    }

    @Test
    @DisplayName("ingestCorpus → 幂等报告 + 审计")
    void ingestCorpus() {
        when(corpusIngestService.ingestCorpus("# 标题\n内容"))
                .thenReturn(new KnowledgeCorpusIngestService.IngestReport(5, 3, 1, 1, List.of("A")));

        var resp = controller.ingestCorpus("# 标题\n内容", auth());

        assertThat(resp.data().parsed()).isEqualTo(5);
        assertThat(resp.data().ingestedTitles()).hasSize(1);
        verify(auditLogService).log(tenantId, userId, "KNOWLEDGE_CORPUS_INGEST", "knowledge_document", null,
                "parsed=5, ingested=3, skipped=1, deferredCrisis=1");
    }

    // ===== 检索 / 列表 / 删除 =====

    @Test
    @DisplayName("search 透传 query 与 topK")
    void search() {
        when(knowledgeBaseService.search(tenantId, "焦虑", 5))
                .thenReturn(List.of(new KnowledgeBaseService.KnowledgeChunk(
                        UUID.randomUUID(), docId, "内容", 0, "考试焦虑", "general", 0.92)));

        var resp = controller.search("焦虑", 5, auth());

        assertThat(resp.data()).hasSize(1);
        assertThat(resp.data().get(0).similarity()).isEqualTo(0.92);
    }

    @Test
    @DisplayName("list 按租户查询（category 可缺省）")
    void list() {
        when(knowledgeBaseService.listDocuments(tenantId, "crisis_intervention"))
                .thenReturn(List.of(Map.of("docId", docId, "title", "危机干预")));

        var resp = controller.list("crisis_intervention", auth());

        assertThat(resp.data()).hasSize(1);
        assertThat(resp.data().get(0).get("title")).isEqualTo("危机干预");
    }

    @Test
    @DisplayName("delete 删除 + 审计")
    void delete() {
        var resp = controller.delete(docId, auth());

        assertThat(resp.code()).isEqualTo(0);
        verify(knowledgeBaseService).deleteDocument(tenantId, docId);
        verify(auditLogService).log(tenantId, userId, "KNOWLEDGE_DELETE", "knowledge_document", docId, null);
    }

    // ===== 审核状态机 =====

    @Test
    @DisplayName("transitionReviewStatus 缺 targetStatus → 400")
    void review_missingTarget() {
        assertThatThrownBy(() -> controller.transitionReviewStatus(docId, new KnowledgeBaseController.ReviewTransitionRequest(null, null, null, null, null, null), auth()))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("transitionReviewStatus 文档不存在 → 404")
    void review_docNotFound() {
        when(knowledgeBaseService.findDocumentStatus(tenantId, docId)).thenReturn(null);

        assertThatThrownBy(() -> controller.transitionReviewStatus(
                docId, new KnowledgeBaseController.ReviewTransitionRequest("published", null, null, null, null, null), auth()))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("transitionReviewStatus 门禁拒绝 → 400 + violations")
    void review_gateRejected() {
        when(knowledgeBaseService.findDocumentStatus(tenantId, docId)).thenReturn("draft");
        when(reviewGateValidator.validateTransition(eq(reviewStateMachine),
                eq(ReviewWorkflowStateMachine.ReviewStatus.DRAFT),
                eq(ReviewWorkflowStateMachine.ReviewStatus.PUBLISHED), any()))
                .thenReturn(new ReviewGateValidator.GateResult(false, List.of("缺循证等级", "缺审核人")));

        assertThatThrownBy(() -> controller.transitionReviewStatus(docId,
                new KnowledgeBaseController.ReviewTransitionRequest("published", "general", null, null, null, null), auth()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("缺循证等级");
        verify(knowledgeBaseService, never()).transitionReviewStatus(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("transitionReviewStatus 成功 → from/to/searchable + 审计")
    void review_success() {
        when(knowledgeBaseService.findDocumentStatus(tenantId, docId)).thenReturn("published");
        when(reviewGateValidator.validateTransition(eq(reviewStateMachine),
                eq(ReviewWorkflowStateMachine.ReviewStatus.PUBLISHED),
                eq(ReviewWorkflowStateMachine.ReviewStatus.DEPRECATED), any()))
                .thenReturn(new ReviewGateValidator.GateResult(true, List.of()));
        when(reviewStateMachine.isSearchable(ReviewWorkflowStateMachine.ReviewStatus.DEPRECATED))
                .thenReturn(false);

        var resp = controller.transitionReviewStatus(docId, new KnowledgeBaseController.ReviewTransitionRequest(
                "deprecated",
                "general", "grade_5_6",
                "manual", "A", "张老师"), auth());

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data().get("from")).isEqualTo("published");
        assertThat(resp.data().get("to")).isEqualTo("deprecated");
        assertThat(resp.data().get("searchable")).isEqualTo(false);
        verify(knowledgeBaseService).transitionReviewStatus(tenantId, docId, "deprecated",
                "grade_5_6", "manual", "A", "张老师");
        verify(auditLogService).log(tenantId, userId, "KNOWLEDGE_REVIEW_TRANSITION",
                "knowledge_document", docId, "PUBLISHED → DEPRECATED, reviewer=张老师");
    }

    @Test
    @DisplayName("transitionReviewStatus 发布成功 → searchable=true")
    void review_publishSearchable() {
        when(knowledgeBaseService.findDocumentStatus(tenantId, docId)).thenReturn("in_review");
        when(reviewGateValidator.validateTransition(eq(reviewStateMachine),
                eq(ReviewWorkflowStateMachine.ReviewStatus.IN_REVIEW),
                eq(ReviewWorkflowStateMachine.ReviewStatus.PUBLISHED), any()))
                .thenReturn(new ReviewGateValidator.GateResult(true, List.of()));
        when(reviewStateMachine.isSearchable(ReviewWorkflowStateMachine.ReviewStatus.PUBLISHED))
                .thenReturn(true);

        var resp = controller.transitionReviewStatus(docId, new KnowledgeBaseController.ReviewTransitionRequest("published", null, null, null, null, null), auth());

        assertThat(resp.data().get("searchable")).isEqualTo(true);
    }

    // ===== 采编工作流 =====

    @Test
    @DisplayName("editorialAction 缺 action → 400")
    void editorial_missingAction() {
        assertThatThrownBy(() -> controller.editorialAction(docId, new KnowledgeBaseController.EditorialActionRequest(null, null, null, null, null, null, null), auth()))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("editorialAction 未知 action → 400")
    void editorial_unknownAction() {
        assertThatThrownBy(() -> controller.editorialAction(docId, new KnowledgeBaseController.EditorialActionRequest("explode", null, null, null, null, null, null), auth()))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("editorialAction submit 成功")
    void editorial_submit() {
        when(editorialWorkflowService.submitForReview(eq(tenantId), eq(userId), eq(docId), any()))
                .thenReturn(new EditorialWorkflowService.TransitionResult(
                        true, "draft", "in_review", false, List.of()));

        var resp = controller.editorialAction(docId, new KnowledgeBaseController.EditorialActionRequest(
                "submit", "general", null, null, null, null, "张老师"), auth());

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data().get("to")).isEqualTo("in_review");
    }

    @Test
    @DisplayName("editorialAction reject 门禁拒绝 → 400")
    void editorial_rejectGate() {
        when(editorialWorkflowService.reject(eq(tenantId), eq(userId), eq(docId), eq("证据不足")))
                .thenReturn(new EditorialWorkflowService.TransitionResult(
                        false, "in_review", "in_review", false, List.of("证据不足", "缺审核人")));

        assertThatThrownBy(() -> controller.editorialAction(
                docId, new KnowledgeBaseController.EditorialActionRequest("reject", null, null, null, null, "证据不足", null), auth()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("证据不足");
    }

    @Test
    @DisplayName("editorialAction publish 成功 → searchable")
    void editorial_publish() {
        when(editorialWorkflowService.publish(eq(tenantId), eq(userId), eq(docId), any()))
                .thenReturn(new EditorialWorkflowService.TransitionResult(
                        true, "in_review", "published", true, List.of()));

        var resp = controller.editorialAction(docId, new KnowledgeBaseController.EditorialActionRequest("publish", null, null, null, null, null, null), auth());

        assertThat(resp.data().get("searchable")).isEqualTo(true);
    }

    @Test
    @DisplayName("editorialAction deprecate 传 reason")
    void editorial_deprecate() {
        when(editorialWorkflowService.deprecate(eq(tenantId), eq(userId), eq(docId), eq("内容过时")))
                .thenReturn(new EditorialWorkflowService.TransitionResult(
                        true, "published", "deprecated", false, List.of()));

        controller.editorialAction(docId, new KnowledgeBaseController.EditorialActionRequest("deprecate", null, null, null, null, "内容过时", null), auth());

        verify(editorialWorkflowService).deprecate(tenantId, userId, docId, "内容过时");
    }

    // ===== 运营报表 =====

    @Test
    @DisplayName("operationalReport 按分类分组 + missedQueries 分割 + 审计")
    void operationalReport() {
        when(knowledgeBaseService.listDocuments(tenantId, null)).thenReturn(List.of(
                Map.of("category", "crisis_intervention", "status", "published"),
                Map.of("category", "crisis_intervention", "status", "draft"),
                Map.of("category", "general", "status", "published")));
        when(editorialWorkflowService.operationalReport(eq(tenantId),
                eq(List.of("q1", "q2")), any())).thenReturn(
                new EditorialWorkflowService.OperationalReport(
                        List.of(new HybridRetrievalService.ContentGap("q1", 3, "general")),
                        List.of(new EditorialWorkflowService.CategoryCoverage("crisis_intervention", 2, 1))));

        var resp = controller.operationalReport("q1,q2", auth());

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data().contentGaps()).hasSize(1);
        assertThat(resp.data().coverage().get(0).published()).isEqualTo(1);
        verify(auditLogService).log(tenantId, userId, "KNOWLEDGE_OPERATIONAL_REPORT", "knowledge_document");
    }

    @Test
    @DisplayName("operationalReport missedQueries 空白 → 空列表；竖线分隔也支持")
    void operationalReport_emptyQueries() {
        when(knowledgeBaseService.listDocuments(tenantId, null)).thenReturn(List.of());
        when(editorialWorkflowService.operationalReport(eq(tenantId), eq(List.of("a", "b")), any()))
                .thenReturn(new EditorialWorkflowService.OperationalReport(List.of(), List.of()));

        controller.operationalReport("a|b", auth());
        controller.operationalReport("  ", auth());
        controller.operationalReport(null, auth());

        verify(editorialWorkflowService).operationalReport(eq(tenantId), eq(List.of("a", "b")), any());
        verify(editorialWorkflowService, org.mockito.Mockito.times(2))
                .operationalReport(eq(tenantId), eq(List.of()), any());
    }
}
