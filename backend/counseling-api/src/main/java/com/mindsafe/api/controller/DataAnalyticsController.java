package com.mindsafe.api.controller;

import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.service.analytics.DataAnalyticsService;
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
 */
@RestController
@RequestMapping("/api/v1/analytics")
public class DataAnalyticsController {

    private final DataAnalyticsService analyticsService;

    public DataAnalyticsController(DataAnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
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
        TenantContext ctx = (TenantContext) auth.getDetails();
        LocalDate date = LocalDate.parse(interventionDate);
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
        TenantContext ctx = (TenantContext) auth.getDetails();
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
        TenantContext ctx = (TenantContext) auth.getDetails();
        return ApiResponse.ok(analyticsService.schoolReport(
                ctx.tenantId(), LocalDate.parse(periodStart), LocalDate.parse(periodEnd)));
    }
}
