package com.mindsafe.api.controller;

import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.domain.entity.PromptVersion;
import com.mindsafe.service.audit.AuditLogService;
import com.mindsafe.service.prompt.PromptEvalGovernance;
import com.mindsafe.service.prompt.PromptEvalScoreReader;
import com.mindsafe.service.prompt.PromptVersionService;
import com.mindsafe.service.prompt.TemplateMatrixRegistry;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Prompt 版本管理 API（AI-005）
 * <p>
 * 功能：版本 CRUD / 激活切换 / A/B 效果对比
 * 权限：仅 admin 角色可访问
 */
@RestController
@RequestMapping("/api/v1/admin/prompts")
public class AdminPromptController {

    private final PromptVersionService promptVersionService;
    private final AuditLogService auditLogService;
    private final TemplateMatrixRegistry templateMatrixRegistry;
    private final PromptEvalGovernance promptEvalGovernance;
    private final PromptEvalScoreReader evalScoreReader;

    public AdminPromptController(PromptVersionService promptVersionService,
                                 AuditLogService auditLogService,
                                 TemplateMatrixRegistry templateMatrixRegistry,
                                 PromptEvalGovernance promptEvalGovernance,
                                 PromptEvalScoreReader evalScoreReader) {
        this.promptVersionService = promptVersionService;
        this.auditLogService = auditLogService;
        this.templateMatrixRegistry = templateMatrixRegistry;
        this.promptEvalGovernance = promptEvalGovernance;
        this.evalScoreReader = evalScoreReader;
    }

    // ===== 版本 CRUD =====

    /** 查询版本列表（按模板 key） */
    @GetMapping("/versions")
    public ApiResponse<List<Map<String, Object>>> listVersions(
            @RequestParam String templateKey,
            @RequestParam(required = false) UUID tenantId) {
        List<PromptVersion> versions = promptVersionService.listVersions(tenantId, templateKey);
        List<Map<String, Object>> result = versions.stream().map(this::toVersionMap).collect(Collectors.toList());
        return ApiResponse.ok(result);
    }

    /** 查询单个版本详情 */
    @GetMapping("/versions/{versionId}")
    public ApiResponse<Map<String, Object>> getVersion(@PathVariable UUID versionId) {
        PromptVersion pv = promptVersionService.getVersionById(versionId);
        if (pv == null) {
            return ApiResponse.ok(Map.of("error", "版本不存在"));
        }
        return ApiResponse.ok(toVersionMap(pv));
    }

    /** 创建新版本 */
    @PostMapping("/versions")
    public ApiResponse<Map<String, Object>> createVersion(
            @RequestBody Map<String, String> body,
            Authentication auth) {
        TenantContext ctx = (TenantContext) auth.getDetails();
        String templateKey = body.get("templateKey");
        String content = body.get("content");
        String description = body.get("description");
        String abGroup = body.getOrDefault("abGroup", "control");
        UUID tenantId = body.containsKey("tenantId") ? UUID.fromString(body.get("tenantId")) : null;

        if (templateKey == null || content == null) {
            return ApiResponse.ok(Map.of("error", "templateKey 和 content 为必填项"));
        }

        PromptVersion pv = promptVersionService.createVersion(
                tenantId, templateKey, content, description, abGroup, ctx.userId());
        return ApiResponse.ok(toVersionMap(pv));
    }

    /**
     * 激活版本（同组下唯一生效）——走发布门禁（G-1 硬化，design/45 §6.1/§7.3）
     * <p>
     * 请求体：reviewer（审校人，<b>必填</b>，不再缺省取登录名）。eval 分数由服务端从库读数
     * （counseling_sessions.prompt_version + quality_scores），不接受自报。
     * 安全关键模板必过红队静态回归，全部门禁通过才写库。
     */
    @PostMapping("/versions/{versionId}/activate")
    public ApiResponse<Void> activateVersion(@PathVariable UUID versionId,
                                             @RequestBody(required = false) Map<String, Object> body,
                                             Authentication auth) {
        TenantContext ctx = (TenantContext) auth.getDetails();
        Map<String, Object> b = body != null ? body : Map.of();
        String reviewer = b.get("reviewer") != null ? String.valueOf(b.get("reviewer")).trim() : "";
        if (reviewer.isBlank()) {
            return ApiResponse.error(ErrorCode.PARAM_INVALID.code(), "reviewer 为必填项（审校人签字）");
        }

        try {
            promptVersionService.activateVersion(versionId, reviewer);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(ErrorCode.RESOURCE_NOT_FOUND.code(), e.getMessage());
        } catch (IllegalStateException e) {
            // 门禁拒绝：保留明细返回（HTTP 200 + success=false，前端统一约定）
            return ApiResponse.error(ErrorCode.PARAM_INVALID.code(), e.getMessage());
        }
        // 操作者留痕（门禁明细留痕由服务层 PROMPT_VERSION_ACTIVATE 承担）
        auditLogService.log(ctx.tenantId(), ctx.userId(), "PROMPT_ACTIVATE", "prompt_version", versionId,
                "reviewer=" + reviewer);
        return ApiResponse.ok(null);
    }

    /** 停用版本（T4 批次B：查询 + 状态更新 + 缓存失效下沉 Service） */
    @PostMapping("/versions/{versionId}/deactivate")
    public ApiResponse<Void> deactivateVersion(@PathVariable UUID versionId) {
        promptVersionService.deactivateVersion(versionId);
        return ApiResponse.ok(null);
    }

    // ===== A/B 效果对比 =====

    /**
     * A/B 效果对比：按 prompt_version 分组统计质量评分均值
     * 关联 counseling_sessions.prompt_version + quality_scores
     */
    @GetMapping("/ab-comparison")
    public ApiResponse<Map<String, Object>> abComparison(
            @RequestParam UUID tenantId,
            @RequestParam(required = false) String templateKey) {
        // T4 批次C：分组统计逻辑整体下沉 PromptVersionService（Controller 不再直查 Mapper）
        return ApiResponse.ok(promptVersionService.abComparison(tenantId, templateKey));
    }

    // ===== 工具方法 =====

    /**
     * PEVAL-003：查询模板矩阵（模板ID × 版本 × 适用人群 × 状态）
     */
    @GetMapping("/matrix")
    public ApiResponse<List<TemplateMatrixRegistry.TemplateEntry>> templateMatrix() {
        return ApiResponse.ok(templateMatrixRegistry.getMatrix());
    }

    /**
     * PEVAL-003：查询红队护栏用例集
     */
    @GetMapping("/guardrails")
    public ApiResponse<List<TemplateMatrixRegistry.GuardrailCase>> guardrails() {
        return ApiResponse.ok(templateMatrixRegistry.getGuardrailCases());
    }

    /**
     * PEVAL-004：灰度放量评估（判断是否可进入下一阶段）。
     * fix-gate：safetyMean / evalDelta 从库读数，拒绝自报分数。
     * blockRate / baselineBlockRate 由调用方从监控系统（如 Prometheus）提供（非 eval 维度）。
     */
    @PostMapping("/rollout-eval")
    public ApiResponse<PromptEvalGovernance.RolloutDecision> evaluateRollout(
            @RequestBody Map<String, Object> body) {
        int stageIndex = body.containsKey("stageIndex") ? ((Number) body.get("stageIndex")).intValue() : 0;
        UUID versionId = body.containsKey("versionId")
                ? UUID.fromString(body.get("versionId").toString()) : null;

        // fix-gate：safetyMean 从库读数（quality_scores.safety_compliance）
        double safetyMean = 1.0;
        String versionTag = null;
        if (versionId != null) {
            PromptVersion version = promptVersionService.getVersionById(versionId);
            if (version != null) {
                versionTag = version.versionTag();
                safetyMean = evalScoreReader.readSafetyMean(versionTag);
            }
        }

        // fix-gate：evalDelta 从库读数（目标版本 vs 基线版本 overall score 差值）
        double evalDelta = 0.0;
        if (versionId != null) {
            PromptVersion target = promptVersionService.getVersionById(versionId);
            if (target != null) {
                PromptEvalScoreReader.EvalStat targetStat = evalScoreReader.read(target.versionTag());
                // 基线取最近一条非当前版本的活跃版本（查询下沉 Service）
                PromptVersion baseline = promptVersionService.findActiveBaseline(target.getTemplateKey(), versionId);
                if (baseline != null) {
                    PromptEvalScoreReader.EvalStat baseStat = evalScoreReader.read(baseline.versionTag());
                    evalDelta = targetStat.overallScore() - baseStat.overallScore();
                }
            }
        }

        // blockRate / baselineBlockRate 仍由调用方提供（来自外部监控系统，非 eval 维度）
        double blockRate = body.containsKey("blockRate") ? ((Number) body.get("blockRate")).doubleValue() : 0.0;
        double baselineBlock = body.containsKey("baselineBlockRate") ? ((Number) body.get("baselineBlockRate")).doubleValue() : 0.0;

        PromptEvalGovernance.RolloutDecision decision = promptEvalGovernance.evaluateRollout(
                stageIndex, safetyMean, blockRate, baselineBlock, evalDelta);
        return ApiResponse.ok(decision);
    }

    private Map<String, Object> toVersionMap(PromptVersion pv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("versionId", pv.getVersionId());
        m.put("tenantId", pv.getTenantId());
        m.put("templateKey", pv.getTemplateKey());
        m.put("version", pv.getVersion());
        m.put("description", pv.getDescription());
        m.put("abGroup", pv.getAbGroup());
        m.put("isActive", pv.getIsActive());
        m.put("createdBy", pv.getCreatedBy());
        m.put("createdAt", pv.getCreatedAt());
        m.put("contentLength", pv.getContent() != null ? pv.getContent().length() : 0);
        return m;
    }
}
