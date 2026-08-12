package com.mindsafe.api.controller;

import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.api.security.SecuritySupport;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.service.analytics.DataAnalyticsService;
import com.mindsafe.service.audit.AuditLogService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * 数据分析 API（DATA-001/002/003）
 * <p>
 * DATA-001: 干预效果量化（前后指标对比 + 效应量）
 * DATA-002: 学生成长轨迹（学期情绪曲线 + 里程碑）
 * DATA-003: 校级报告数据聚合（月度/学期统计）
 * <p>
 * 权限：教师/管理员可访问
 * <p>
 * AUD-013：三端点输出个人级数据（studentUserId/里程碑/风险时间线），必须留审计日志。
 * BA-08（DOC-074）：显式冻结——前端当前未接线（仅 OpenAPI 快照登记），服务端能力保留供
 * teacher-web 后续排期接入；冻结期间禁止删除端点或变更响应结构（DataAnalyticsService 双 trend
 * 已合并为 buildWeeklySessionTrend，sessionFrequency 响应 key 统一为 sessions）。
 */
@RestController
@RequestMapping("/api/v1/analytics")
public class DataAnalyticsController {

    private final DataAnalyticsService analyticsService;
    private final AuditLogService auditLogService;

    public DataAnalyticsController(DataAnalyticsService analyticsService, AuditLogService auditLogService) {
        this.analyticsService = analyticsService;
        this.auditLogService = auditLogService;
    }

    /**
     * DATA-001: 干预效果分析
     * 对比干预日期前后的核心指标（负面情绪占比/风险频率/满意度/参与度）
     */
    @GetMapping("/intervention-effect")
    public ApiResponse<Map<String, Object>> interventionEffect(
            Authentication auth,
            @RequestParam UUID studentUserId,
            @RequestParam String interventionDate,
            @RequestParam(defaultValue = "30") int windowDays) {
        TenantContext ctx = SecuritySupport.requireContext(auth);
        LocalDate date = LocalDate.parse(interventionDate);
        // AUD-013：个人级数据访问留痕
        auditLogService.log(ctx.tenantId(), ctx.userId(), "analytics.intervention-effect",
                "student", studentUserId, "windowDays=" + windowDays + ", interventionDate=" + interventionDate);
        return ApiResponse.ok(analyticsService.interventionEffect(ctx.tenantId(), studentUserId, date, windowDays));
    }

    /**
     * DATA-002: 学生成长轨迹
     * 学期维度情绪曲线 + 里程碑 + 风险时间线 + 会话频率
     */
    @GetMapping("/growth-trajectory")
    public ApiResponse<Map<String, Object>> growthTrajectory(
            Authentication auth,
            @RequestParam UUID studentUserId,
            @RequestParam String semesterStart,
            @RequestParam String semesterEnd) {
        TenantContext ctx = SecuritySupport.requireContext(auth);
        // AUD-013：个人级数据访问留痕
        auditLogService.log(ctx.tenantId(), ctx.userId(), "analytics.growth-trajectory",
                "student", studentUserId, "semester=" + semesterStart + "~" + semesterEnd);
        return ApiResponse.ok(analyticsService.growthTrajectory(
                ctx.tenantId(), studentUserId,
                LocalDate.parse(semesterStart), LocalDate.parse(semesterEnd)));
    }

    /**
     * DATA-003: 校级报告
     * 月度/学期 anonymized 统计（概览/风险分布/满意度/AI质量/周趋势）
     */
    @GetMapping("/school-report")
    public ApiResponse<Map<String, Object>> schoolReport(
            Authentication auth,
            @RequestParam String periodStart,
            @RequestParam String periodEnd) {
        TenantContext ctx = SecuritySupport.requireContext(auth);
        // AUD-013：校级聚合数据访问留痕（含风险分布等敏感聚合）
        auditLogService.log(ctx.tenantId(), ctx.userId(), "analytics.school-report",
                "tenant", null, "period=" + periodStart + "~" + periodEnd);
        return ApiResponse.ok(analyticsService.schoolReport(
                ctx.tenantId(), LocalDate.parse(periodStart), LocalDate.parse(periodEnd)));
    }
}
