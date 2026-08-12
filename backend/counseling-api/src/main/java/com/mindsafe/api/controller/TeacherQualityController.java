package com.mindsafe.api.controller;

import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.api.security.SecuritySupport;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.service.audit.AuditLogService;
import com.mindsafe.service.teacher.TeacherQualityService;
import com.mindsafe.service.teacher.TeacherService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 教师端质量监控 API（P1-3 审计修复：从 TeacherController 拆分）
 * <p>
 * 职责：LLM-as-Judge 质量评分查询、低分标记、AI 统计概览、会话抽检回放。
 * C3（2026-08-05）：查询与聚合逻辑下沉 TeacherQualityService，Controller 仅保留
 * HTTP 编排（参数绑定 / 审计日志 / 结果包装）。
 */
@RestController
@RequestMapping("/api/v1")
public class TeacherQualityController {

    private final TeacherService teacherService;
    private final TeacherQualityService teacherQualityService;
    private final AuditLogService auditLogService;

    public TeacherQualityController(TeacherService teacherService,
                                    TeacherQualityService teacherQualityService,
                                    AuditLogService auditLogService) {
        this.teacherService = teacherService;
        this.teacherQualityService = teacherQualityService;
        this.auditLogService = auditLogService;
    }

    /** 质量监控：低分会话列表（rating <= 2） */
    @GetMapping("/teacher/quality/flagged")
    public ApiResponse<List<Map<String, Object>>> getFlaggedSessions(Authentication auth) {
        TenantContext ctx = SecuritySupport.requireContext(auth);
        return ApiResponse.ok(teacherQualityService.flaggedSessions(ctx.tenantId()));
    }

    /** 质量监控：概览指标（BACK-001：班主任仅本班统计） */
    @GetMapping("/teacher/quality/stats")
    public ApiResponse<Map<String, Object>> getQualityStats(Authentication auth) {
        TenantContext ctx = SecuritySupport.requireContext(auth);
        String classScope = teacherService.resolveClassScope(ctx.tenantId(), ctx.userId(), ctx.userType());
        var stats = teacherService.getSatisfactionStats(ctx.tenantId(), classScope);
        long flaggedCount = stats.distribution().stream()
                .filter(d -> d.stars() <= 2).mapToLong(d -> d.count()).sum();
        double flagRate = stats.totalRated() > 0 ? (double) flaggedCount / stats.totalRated() * 100 : 0;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalRated", stats.totalRated());
        result.put("avgRating", stats.avgRating());
        result.put("flaggedCount", flaggedCount);
        result.put("flagRate", Math.round(flagRate * 10) / 10.0);
        result.put("recentAvg", stats.recentAvg());
        return ApiResponse.ok(result);
    }

    /**
     * 质量评分列表（支持筛选：仅低分标记 / 学生 / 分页）
     */
    @GetMapping("/teacher/quality/scores")
    public ApiResponse<Map<String, Object>> getQualityScores(
            Authentication auth,
            @RequestParam(required = false) Boolean flaggedOnly,
            @RequestParam(required = false) UUID studentUserId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        TenantContext ctx = SecuritySupport.requireContext(auth);
        return ApiResponse.ok(teacherQualityService.qualityScores(ctx.tenantId(), flaggedOnly, studentUserId, page, size));
    }

    /**
     * AI 质量统计概览（LLM-as-Judge 评分均值 / 低分率）
     */
    @GetMapping("/teacher/quality/ai-stats")
    public ApiResponse<Map<String, Object>> getAiQualityStats(Authentication auth) {
        TenantContext ctx = SecuritySupport.requireContext(auth);
        return ApiResponse.ok(teacherQualityService.aiQualityStats(ctx.tenantId()));
    }

    /**
     * 会话抽检回放（对话摘要 + 质量评分叠加）；会话不存在或跨租户返回 error 提示
     */
    @GetMapping("/teacher/quality/sessions/{sessionId}/replay")
    public ApiResponse<Map<String, Object>> replaySession(@PathVariable UUID sessionId, Authentication auth) {
        TenantContext ctx = SecuritySupport.requireContext(auth);
        auditLogService.log(ctx.tenantId(), (UUID) auth.getPrincipal(), "QUALITY_REPLAY", "counseling_session", sessionId, null);

        Map<String, Object> replay = teacherQualityService.replaySession(ctx.tenantId(), sessionId);
        if (replay == null) {
            return ApiResponse.ok(Map.of("error", "会话不存在"));
        }
        return ApiResponse.ok(replay);
    }
}
