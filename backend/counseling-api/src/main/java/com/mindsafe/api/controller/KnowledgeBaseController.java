package com.mindsafe.api.controller;

import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.api.security.SecuritySupport;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.service.audit.AuditLogService;
import com.mindsafe.service.knowledge.EditorialWorkflowService;
import com.mindsafe.service.knowledge.KnowledgeBaseService;
import com.mindsafe.service.knowledge.KnowledgeCorpusIngestService;
import com.mindsafe.service.knowledge.KnowledgeMetadata;
import com.mindsafe.service.knowledge.ReviewGateValidator;
import com.mindsafe.service.knowledge.ReviewWorkflowStateMachine;
import com.mindsafe.service.knowledge.ReviewWorkflowStateMachine.ReviewStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 心理知识库管理 API（AI-006：RAG 知识库）
 * <p>
 * 功能：文档摄入 / 检索测试 / 列表 / 删除
 * 权限：教师/管理员
 */
@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeCorpusIngestService corpusIngestService;
    private final AuditLogService auditLogService;
    private final ReviewWorkflowStateMachine reviewStateMachine;
    private final ReviewGateValidator reviewGateValidator;
    private final EditorialWorkflowService editorialWorkflowService;

    public KnowledgeBaseController(KnowledgeBaseService knowledgeBaseService,
                                   KnowledgeCorpusIngestService corpusIngestService,
                                   AuditLogService auditLogService,
                                   ReviewWorkflowStateMachine reviewStateMachine,
                                   ReviewGateValidator reviewGateValidator,
                                   EditorialWorkflowService editorialWorkflowService) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.corpusIngestService = corpusIngestService;
        this.auditLogService = auditLogService;
        this.reviewStateMachine = reviewStateMachine;
        this.reviewGateValidator = reviewGateValidator;
        this.editorialWorkflowService = editorialWorkflowService;
    }

    /** 摄入文档（分块 + 嵌入） */
    @PostMapping("/documents")
    public ApiResponse<Map<String, Object>> ingest(
            @RequestBody Map<String, String> body, Authentication auth) {
        TenantContext ctx = SecuritySupport.requireContext(auth);
        String title = body.get("title");
        String category = body.getOrDefault("category", "general");
        String content = body.get("content");
        String source = body.get("source");

        if (title == null || content == null || content.isBlank()) {
            return ApiResponse.ok(Map.of("error", "title 和 content 为必填项"));
        }

        UUID docId = knowledgeBaseService.ingestDocument(
                ctx.tenantId(), title, category, content, source);

        auditLogService.log(ctx.tenantId(), ctx.userId(), "KNOWLEDGE_INGEST",
                "knowledge_document", docId, "title=" + title + ", category=" + category);

        return ApiResponse.ok(Map.of("docId", docId, "message", "文档摄入成功"));
    }

    /**
     * 批量入库审核语料（KB-101）
     * <p>
     * 请求体为已审核语料 Markdown 全文（data/knowledge-base/01-首批入库语料_v1.md），
     * 幂等可重复执行；crisis_intervention 类自动缓入（铁律：不进学生对话 RAG）。
     */
    @PostMapping(value = "/corpus", consumes = "text/plain;charset=UTF-8")
    public ApiResponse<KnowledgeCorpusIngestService.IngestReport> ingestCorpus(
            @RequestBody String corpusMarkdown, Authentication auth) {
        TenantContext ctx = SecuritySupport.requireContext(auth);
        KnowledgeCorpusIngestService.IngestReport report =
                corpusIngestService.ingestCorpus(corpusMarkdown);

        auditLogService.log(ctx.tenantId(), ctx.userId(), "KNOWLEDGE_CORPUS_INGEST",
                "knowledge_document", null,
                "parsed=" + report.parsed() + ", ingested=" + report.ingested()
                        + ", skipped=" + report.skippedExisting() + ", deferredCrisis=" + report.deferredCrisis());

        return ApiResponse.ok(report);
    }

    /** 检索测试（验证 RAG 效果） */
    @GetMapping("/search")
    public ApiResponse<List<KnowledgeBaseService.KnowledgeChunk>> search(
            @RequestParam String query,
            @RequestParam(defaultValue = "3") int topK,
            Authentication auth) {
        TenantContext ctx = SecuritySupport.requireContext(auth);
        List<KnowledgeBaseService.KnowledgeChunk> results =
                knowledgeBaseService.search(ctx.tenantId(), query, topK);
        return ApiResponse.ok(results);
    }

    /** 文档列表 */
    @GetMapping("/documents")
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestParam(required = false) String category,
            Authentication auth) {
        TenantContext ctx = SecuritySupport.requireContext(auth);
        return ApiResponse.ok(knowledgeBaseService.listDocuments(ctx.tenantId(), category));
    }

    /** 删除文档 */
    @DeleteMapping("/documents/{docId}")
    public ApiResponse<Void> delete(@PathVariable UUID docId, Authentication auth) {
        TenantContext ctx = SecuritySupport.requireContext(auth);
        knowledgeBaseService.deleteDocument(ctx.tenantId(), docId);
        auditLogService.log(ctx.tenantId(), ctx.userId(), "KNOWLEDGE_DELETE",
                "knowledge_document", docId, null);
        return ApiResponse.ok(null);
    }

    /**
     * 知识审核状态转移（KB-102 接线：ReviewWorkflowStateMachine + ReviewGateValidator）
     * <p>
     * 状态流转：draft → in_review → published → deprecated。
     * 门禁校验：提交审核需分类+年级段，发布需循证等级+审核人+红队校验。
     */
    @PutMapping("/documents/{docId}/review")
    public ApiResponse<Map<String, Object>> transitionReviewStatus(
            @PathVariable UUID docId,
            @RequestBody Map<String, String> body,
            Authentication auth) {
        TenantContext ctx = SecuritySupport.requireContext(auth);

        String targetStatus = body.get("targetStatus");
        if (targetStatus == null || targetStatus.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "缺少 targetStatus 参数");
        }

        // 当前状态以 DB 真实值为准（不信请求体，防绕过状态机）
        String dbStatus = knowledgeBaseService.findDocumentStatus(ctx.tenantId(), docId);
        if (dbStatus == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "知识文档不存在: " + docId);
        }

        ReviewStatus from = ReviewWorkflowStateMachine.fromDbStatus(dbStatus);
        ReviewStatus to = ReviewWorkflowStateMachine.fromDbStatus(targetStatus);

        // 构建元数据（从请求体提取门禁所需字段）
        KnowledgeMetadata metadata = new KnowledgeMetadata(
                docId.toString(),
                body.get("category"),
                body.get("gradeBand"),
                body.get("sourceType"),
                body.get("evidenceLevel"),
                from,
                0,
                body.get("reviewer"),
                null,
                "crisis_intervention".equals(body.get("category")));

        // 状态机 + 门禁组合校验
        ReviewGateValidator.GateResult gateResult =
                reviewGateValidator.validateTransition(reviewStateMachine, from, to, metadata);

        if (!gateResult.passed()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "门禁校验失败: " + String.join("; ", gateResult.violations()));
        }

        // 门禁通过 → 状态与审核字段落库（KB-102，V30）
        knowledgeBaseService.transitionReviewStatus(ctx.tenantId(), docId,
                ReviewWorkflowStateMachine.toDbStatus(to),
                body.get("gradeBand"), body.get("sourceType"),
                body.get("evidenceLevel"), body.get("reviewer"));

        auditLogService.log(ctx.tenantId(), ctx.userId(), "KNOWLEDGE_REVIEW_TRANSITION",
                "knowledge_document", docId,
                from + " → " + to + ", reviewer=" + body.get("reviewer"));

        return ApiResponse.ok(Map.of(
                "docId", docId,
                "from", ReviewWorkflowStateMachine.toDbStatus(from),
                "to", ReviewWorkflowStateMachine.toDbStatus(to),
                "searchable", reviewStateMachine.isSearchable(to)
        ));
    }

    /**
     * 运营侧采编工作流（G-2，design/49 §五）：采编流水线统一入口
     * <p>
     * 请求体：action=submit（提交审核）/publish（发布）/reject（驳回）/deprecate（下线）；
     * 门禁所需字段 category/gradeBand/sourceType/evidenceLevel/reviewer；驳回/下线附 reason。
     * 门禁不过不落库，留痕由服务层 audit_logs 承担。
     */
    @PostMapping("/documents/{docId}/editorial")
    public ApiResponse<Map<String, Object>> editorialAction(
            @PathVariable UUID docId,
            @RequestBody Map<String, String> body,
            Authentication auth) {
        TenantContext ctx = SecuritySupport.requireContext(auth);
        String action = body.get("action");
        if (action == null || action.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "缺少 action 参数（submit/publish/reject/deprecate）");
        }

        EditorialWorkflowService.EditorialRequest request = new EditorialWorkflowService.EditorialRequest(
                body.get("category"), body.get("gradeBand"), body.get("sourceType"),
                body.get("evidenceLevel"), body.get("reviewer"));

        EditorialWorkflowService.TransitionResult result = switch (action) {
            case "submit" -> editorialWorkflowService.submitForReview(
                    ctx.tenantId(), ctx.userId(), docId, request);
            case "publish" -> editorialWorkflowService.publish(
                    ctx.tenantId(), ctx.userId(), docId, request);
            case "reject" -> editorialWorkflowService.reject(
                    ctx.tenantId(), ctx.userId(), docId, body.get("reason"));
            case "deprecate" -> editorialWorkflowService.deprecate(
                    ctx.tenantId(), ctx.userId(), docId, body.get("reason"));
            default -> null;
        };

        if (result == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "未知 action: " + action);
        }
        if (!result.success()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "采编门禁拒绝: " + String.join("; ", result.violations()));
        }

        return ApiResponse.ok(Map.of(
                "docId", docId,
                "from", result.fromStatus(),
                "to", result.toStatus(),
                "searchable", result.searchable()
        ));
    }

    /**
     * 运营报表（design/49 §五 运营闭环）：暴露 operationalReport 主链路。
     * <p>
     * 覆盖统计从库内 knowledge_documents 实时聚合（分类 × 审核状态）；
     * missedQueries 为可选参数（运营从检索日志采样的未命中查询，逗号分隔，
     * 用于产出内容缺口清单；无持久化 miss 表，免 schema 变更）。
     */
    @GetMapping("/editorial/report")
    public ApiResponse<EditorialWorkflowService.OperationalReport> operationalReport(
            @RequestParam(value = "missedQueries", required = false) String missedQueriesParam,
            Authentication auth) {
        TenantContext ctx = SecuritySupport.requireContext(auth);

        // 分类 × 状态覆盖输入：文档列表（含全部审核状态）
        Map<String, List<String>> docsByCategory =
                knowledgeBaseService.listDocuments(ctx.tenantId(), null).stream()
                        .collect(Collectors.groupingBy(
                                row -> String.valueOf(row.get("category")),
                                Collectors.mapping(row -> String.valueOf(row.get("status")),
                                        Collectors.toList())));

        List<String> missedQueries = missedQueriesParam == null || missedQueriesParam.isBlank()
                ? List.of()
                : List.of(missedQueriesParam.split("[,|]"));

        EditorialWorkflowService.OperationalReport report =
                editorialWorkflowService.operationalReport(ctx.tenantId(), missedQueries, docsByCategory);

        auditLogService.log(ctx.tenantId(), ctx.userId(), "KNOWLEDGE_OPERATIONAL_REPORT",
                "knowledge_document");

        return ApiResponse.ok(report);
    }
}
