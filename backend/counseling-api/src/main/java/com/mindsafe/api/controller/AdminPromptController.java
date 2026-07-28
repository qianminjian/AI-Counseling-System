package com.mindsafe.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.PromptVersion;
import com.mindsafe.domain.entity.QualityScore;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.domain.mapper.PromptVersionMapper;
import com.mindsafe.domain.mapper.QualityScoreMapper;
import com.mindsafe.service.audit.AuditLogService;
import com.mindsafe.service.prompt.PromptVersionService;
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
    private final PromptVersionMapper promptVersionMapper;
    private final CounselingSessionMapper sessionMapper;
    private final QualityScoreMapper qualityScoreMapper;
    private final AuditLogService auditLogService;

    public AdminPromptController(PromptVersionService promptVersionService,
                                 PromptVersionMapper promptVersionMapper,
                                 CounselingSessionMapper sessionMapper,
                                 QualityScoreMapper qualityScoreMapper,
                                 AuditLogService auditLogService) {
        this.promptVersionService = promptVersionService;
        this.promptVersionMapper = promptVersionMapper;
        this.sessionMapper = sessionMapper;
        this.qualityScoreMapper = qualityScoreMapper;
        this.auditLogService = auditLogService;
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
        PromptVersion pv = promptVersionMapper.selectById(versionId);
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

    /** 激活版本（同组下唯一生效） */
    @PostMapping("/versions/{versionId}/activate")
    public ApiResponse<Void> activateVersion(@PathVariable UUID versionId, Authentication auth) {
        TenantContext ctx = (TenantContext) auth.getDetails();
        promptVersionService.activateVersion(versionId);
        auditLogService.log(ctx.tenantId(), ctx.userId(), "PROMPT_ACTIVATE", "prompt_version", versionId, null);
        return ApiResponse.ok(null);
    }

    /** 停用版本 */
    @PostMapping("/versions/{versionId}/deactivate")
    public ApiResponse<Void> deactivateVersion(@PathVariable UUID versionId) {
        PromptVersion pv = promptVersionMapper.selectById(versionId);
        if (pv != null) {
            pv.setIsActive(false);
            pv.setUpdatedAt(java.time.Instant.now());
            promptVersionMapper.updateById(pv);
            promptVersionService.invalidateCache();
        }
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

        // 查询该租户所有有 prompt_version 记录的会话
        LambdaQueryWrapper<CounselingSession> sessionWrapper = new LambdaQueryWrapper<CounselingSession>()
                .eq(CounselingSession::getTenantId, tenantId)
                .isNotNull(CounselingSession::getPromptVersion);
        if (templateKey != null) {
            sessionWrapper.likeRight(CounselingSession::getPromptVersion, templateKey + ":");
        }
        List<CounselingSession> sessions = sessionMapper.selectList(sessionWrapper);

        if (sessions.isEmpty()) {
            return ApiResponse.ok(Map.of("groups", List.of(), "totalSessions", 0));
        }

        // 按 ab_group 分组（从 versionTag 解析：SYS_001:v3:treatment_a → treatment_a）
        Map<String, List<UUID>> groupSessions = new LinkedHashMap<>();
        for (CounselingSession s : sessions) {
            String tag = s.getPromptVersion();
            String group = tag.contains(":") ? tag.substring(tag.lastIndexOf(':') + 1) : "unknown";
            groupSessions.computeIfAbsent(group, k -> new ArrayList<>()).add(s.getSessionId());
        }

        // 每组计算质量评分均值
        List<Map<String, Object>> groups = new ArrayList<>();
        for (Map.Entry<String, List<UUID>> entry : groupSessions.entrySet()) {
            List<UUID> sessionIds = entry.getValue();
            List<QualityScore> scores = qualityScoreMapper.selectList(
                    new LambdaQueryWrapper<QualityScore>()
                            .in(QualityScore::getSessionId, sessionIds));

            Map<String, Object> groupStat = new LinkedHashMap<>();
            groupStat.put("abGroup", entry.getKey());
            groupStat.put("sessionCount", sessionIds.size());
            groupStat.put("scoredCount", scores.size());

            if (!scores.isEmpty()) {
                double avgEmpathy = scores.stream()
                        .filter(q -> q.getEmpathyScore() != null)
                        .mapToDouble(q -> q.getEmpathyScore().doubleValue()).average().orElse(0);
                double avgCbt = scores.stream()
                        .filter(q -> q.getCbtCompletion() != null)
                        .mapToDouble(q -> q.getCbtCompletion().doubleValue()).average().orElse(0);
                double avgSafety = scores.stream()
                        .filter(q -> q.getSafetyCompliance() != null)
                        .mapToDouble(q -> q.getSafetyCompliance().doubleValue()).average().orElse(0);
                double avgEngagement = scores.stream()
                        .filter(q -> q.getEngagementScore() != null)
                        .mapToDouble(q -> q.getEngagementScore().doubleValue()).average().orElse(0);
                groupStat.put("avgEmpathy", Math.round(avgEmpathy * 100) / 100.0);
                groupStat.put("avgCbtCompletion", Math.round(avgCbt * 100) / 100.0);
                groupStat.put("avgSafetyCompliance", Math.round(avgSafety * 100) / 100.0);
                groupStat.put("avgEngagement", Math.round(avgEngagement * 100) / 100.0);
                double overall = (avgEmpathy + avgCbt + avgSafety + avgEngagement) / 4;
                groupStat.put("avgOverall", Math.round(overall * 100) / 100.0);
            }
            groups.add(groupStat);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalSessions", sessions.size());
        result.put("groups", groups);
        return ApiResponse.ok(result);
    }

    // ===== 工具方法 =====

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
