package com.mindsafe.service.knowledge;

import com.mindsafe.service.audit.AuditLogService;
import com.mindsafe.service.knowledge.ReviewWorkflowStateMachine.ReviewStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 运营侧采编工作流编排（G-2，design/49 §五 + 运营余量）
 * <p>
 * 把采编-审核-发布-下线流水线编排为统一入口（组合
 * {@link ReviewWorkflowStateMachine} 状态机 + {@link ReviewGateValidator} 门禁），
 * 并提供运营报表（内容缺口聚合 + 分类覆盖统计，接 KB-103）。
 * <ul>
 *   <li>铁律：仅 published 内容可被 RAG 检索；deprecated 不可恢复（须重走审核）</li>
 *   <li>门禁不过不落库；每次合法转移经 audit_logs 留痕（免 schema 变更红线）</li>
 *   <li>危机类内容（crisis_intervention）强制 safety_sensitive=true，与 design/04/14 单一事实源对齐</li>
 * </ul>
 */
@Service
public class EditorialWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(EditorialWorkflowService.class);

    private static final String CRISIS_CATEGORY = "crisis_intervention";

    private final KnowledgeBaseService knowledgeBaseService;
    private final ReviewWorkflowStateMachine stateMachine;
    private final ReviewGateValidator gateValidator;
    private final HybridRetrievalService retrievalService;
    private final AuditLogService auditLogService;

    public EditorialWorkflowService(KnowledgeBaseService knowledgeBaseService,
                                    ReviewWorkflowStateMachine stateMachine,
                                    ReviewGateValidator gateValidator,
                                    HybridRetrievalService retrievalService,
                                    AuditLogService auditLogService) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.stateMachine = stateMachine;
        this.gateValidator = gateValidator;
        this.retrievalService = retrievalService;
        this.auditLogService = auditLogService;
    }

    // ===== 采编流水线 =====

    /** 采编请求（门禁所需元数据字段） */
    public record EditorialRequest(
            String category,
            String gradeBand,
            String sourceType,
            String evidenceLevel,
            String reviewer
    ) {
    }

    /** 转移结果 */
    public record TransitionResult(
            boolean success,
            String fromStatus,
            String toStatus,
            boolean searchable,
            List<String> violations
    ) {
        static TransitionResult fail(String from, List<String> violations) {
            return new TransitionResult(false, from, null, false, violations);
        }
    }

    /** 提交审核：draft → in_review（轻量门禁：分类 + 年级段） */
    public TransitionResult submitForReview(UUID tenantId, UUID operatorId,
                                            UUID docId, EditorialRequest request) {
        return doTransition(tenantId, operatorId, docId, request,
                ReviewStatus.IN_REVIEW, "EDITORIAL_SUBMIT_REVIEW", null);
    }

    /** 发布：in_review → published（四门禁：专业/合规/红队/留痕） */
    public TransitionResult publish(UUID tenantId, UUID operatorId,
                                    UUID docId, EditorialRequest request) {
        return doTransition(tenantId, operatorId, docId, request,
                ReviewStatus.PUBLISHED, "EDITORIAL_PUBLISH", null);
    }

    /** 驳回：in_review → draft（附驳回原因） */
    public TransitionResult reject(UUID tenantId, UUID operatorId, UUID docId, String reason) {
        return doTransition(tenantId, operatorId, docId, null,
                ReviewStatus.DRAFT, "EDITORIAL_REJECT", reason);
    }

    /** 下线：published → deprecated（附下线原因，不可恢复） */
    public TransitionResult deprecate(UUID tenantId, UUID operatorId, UUID docId, String reason) {
        return doTransition(tenantId, operatorId, docId, null,
                ReviewStatus.DEPRECATED, "EDITORIAL_DEPRECATE", reason);
    }

    private TransitionResult doTransition(UUID tenantId, UUID operatorId, UUID docId,
                                          EditorialRequest request, ReviewStatus to,
                                          String auditAction, String reason) {
        // 当前状态以 DB 真实值为准（防绕过状态机）
        String dbStatus = knowledgeBaseService.findDocumentStatus(docId);
        if (dbStatus == null) {
            return TransitionResult.fail(null, List.of("知识文档不存在: " + docId));
        }
        ReviewStatus from = ReviewWorkflowStateMachine.fromDbStatus(dbStatus);

        // 元数据（危机类强制 safety_sensitive=true，与 04/14 单一事实源对齐）
        EditorialRequest req = request != null ? request
                : new EditorialRequest(null, null, null, null, null);
        boolean crisis = CRISIS_CATEGORY.equals(req.category());
        KnowledgeMetadata metadata = new KnowledgeMetadata(
                docId.toString(), req.category(), req.gradeBand(), req.sourceType(),
                req.evidenceLevel(), from, 0, req.reviewer(),
                to == ReviewStatus.PUBLISHED ? Instant.now() : null, crisis);

        // 状态机 + 门禁组合校验
        ReviewGateValidator.GateResult gate =
                gateValidator.validateTransition(stateMachine, from, to, metadata);
        if (!gate.passed()) {
            log.warn("采编工作流门禁拒绝: docId={}, {} → {}, violations={}",
                    docId, from, to, gate.violations());
            return TransitionResult.fail(ReviewWorkflowStateMachine.toDbStatus(from), gate.violations());
        }

        // 落库（提交审核阶段 reviewer 不落，发布阶段才签字留痕）
        String toDb = ReviewWorkflowStateMachine.toDbStatus(to);
        knowledgeBaseService.transitionReviewStatus(docId, toDb,
                req.gradeBand(), req.sourceType(), req.evidenceLevel(),
                to == ReviewStatus.PUBLISHED ? req.reviewer() : null);

        // 审计留痕
        String detail = from + " → " + to
                + (req.reviewer() != null ? ", reviewer=" + req.reviewer() : "")
                + (reason != null ? ", reason=" + reason : "");
        auditLogService.log(tenantId, operatorId, auditAction, "knowledge_document", docId, detail);
        log.info("采编工作流状态转移: docId={}, {}", docId, detail);

        return new TransitionResult(true,
                ReviewWorkflowStateMachine.toDbStatus(from), toDb,
                stateMachine.isSearchable(to), List.of());
    }

    // ===== 运营报表 =====

    /** 分类覆盖统计 */
    public record CategoryCoverage(String category, int total, int published) {
    }

    /** 运营报表：内容缺口清单 + 分类覆盖统计 */
    public record OperationalReport(
            List<HybridRetrievalService.ContentGap> contentGaps,
            List<CategoryCoverage> coverage
    ) {
    }

    /**
     * 产出运营报表（周期性运营：高频未命中 → 内容缺口清单供采编补全）。
     *
     * @param tenantId       租户 ID（预留多租户过滤）
     * @param missedQueries  未命中查询日志
     * @param docsByCategory 分类 → 文档状态列表（覆盖统计输入）
     * @return 运营报表
     */
    public OperationalReport operationalReport(UUID tenantId,
                                               List<String> missedQueries,
                                               Map<String, List<String>> docsByCategory) {
        List<HybridRetrievalService.ContentGap> gaps = missedQueries == null
                ? List.of() : retrievalService.identifyContentGaps(missedQueries);

        List<CategoryCoverage> coverage = new ArrayList<>();
        if (docsByCategory != null) {
            Map<String, CategoryCoverage> sorted = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> e : docsByCategory.entrySet()) {
                List<String> statuses = e.getValue() == null ? List.of() : e.getValue();
                long published = statuses.stream()
                        .map(ReviewWorkflowStateMachine::fromDbStatus)
                        .filter(stateMachine::isSearchable)
                        .count();
                sorted.put(e.getKey(), new CategoryCoverage(e.getKey(), statuses.size(), (int) published));
            }
            coverage.addAll(sorted.values());
        }

        return new OperationalReport(gaps, coverage);
    }
}
