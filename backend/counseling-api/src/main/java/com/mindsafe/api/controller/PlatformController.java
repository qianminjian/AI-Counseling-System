package com.mindsafe.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.domain.entity.*;
import com.mindsafe.domain.mapper.*;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 平台管理后台 API（SaaS 运营者视角）
 * <p>
 * 跨租户统计：学校数 / 学生数 / 会话量 / 活跃度 / 风险概览
 */
@RestController
@RequestMapping("/api/v1/platform")
public class PlatformController {

    private final TenantMapper tenantMapper;
    private final SchoolMapper schoolMapper;
    private final UserMapper userMapper;
    private final CounselingSessionMapper sessionMapper;
    private final RiskEventMapper riskEventMapper;

    public PlatformController(TenantMapper tenantMapper, SchoolMapper schoolMapper,
                              UserMapper userMapper, CounselingSessionMapper sessionMapper,
                              RiskEventMapper riskEventMapper) {
        this.tenantMapper = tenantMapper;
        this.schoolMapper = schoolMapper;
        this.userMapper = userMapper;
        this.sessionMapper = sessionMapper;
        this.riskEventMapper = riskEventMapper;
    }

    /** 平台总览（跨租户聚合） */
    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> getOverview() {
        List<Tenant> tenants = tenantMapper.selectList(
                new LambdaQueryWrapper<Tenant>().eq(Tenant::getStatus, "active"));

        long totalSchools = schoolMapper.selectCount(
                new LambdaQueryWrapper<School>().eq(School::getStatus, "active"));
        long totalStudents = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUserType, "student").eq(User::getStatus, "active"));
        long totalTeachers = userMapper.selectCount(
                new LambdaQueryWrapper<User>()
                        .in(User::getUserType, "teacher", "psych_teacher", "class_teacher", "admin")
                        .eq(User::getStatus, "active"));
        long totalSessions = sessionMapper.selectCount(null);

        // 近 7 天活跃（有会话的学生数）
        Instant weekAgo = Instant.now().minus(7, ChronoUnit.DAYS);
        long weeklySessions = sessionMapper.selectCount(
                new LambdaQueryWrapper<CounselingSession>().ge(CounselingSession::getStartedAt, weekAgo));

        // 风险事件统计
        long totalAlerts = riskEventMapper.selectCount(null);
        long openAlerts = riskEventMapper.selectCount(
                new LambdaQueryWrapper<RiskEvent>().eq(RiskEvent::getStatus, "open"));

        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("tenantCount", tenants.size());
        overview.put("schoolCount", totalSchools);
        overview.put("studentCount", totalStudents);
        overview.put("teacherCount", totalTeachers);
        overview.put("totalSessions", totalSessions);
        overview.put("weeklySessions", weeklySessions);
        overview.put("totalAlerts", totalAlerts);
        overview.put("openAlerts", openAlerts);
        return ApiResponse.ok(overview);
    }

    /** 租户列表（含各校学生/教师数） */
    @GetMapping("/tenants")
    public ApiResponse<List<Map<String, Object>>> getTenants() {
        List<Tenant> tenants = tenantMapper.selectList(
                new LambdaQueryWrapper<Tenant>().orderByDesc(Tenant::getCreatedAt));

        List<Map<String, Object>> result = tenants.stream().map(t -> {
            long schools = schoolMapper.selectCount(
                    new LambdaQueryWrapper<School>().eq(School::getTenantId, t.getTenantId()));
            long students = userMapper.selectCount(
                    new LambdaQueryWrapper<User>()
                            .eq(User::getTenantId, t.getTenantId())
                            .eq(User::getUserType, "student"));
            long teachers = userMapper.selectCount(
                    new LambdaQueryWrapper<User>()
                            .eq(User::getTenantId, t.getTenantId())
                            .in(User::getUserType, "teacher", "psych_teacher", "class_teacher", "admin"));
            long sessions = sessionMapper.selectCount(
                    new LambdaQueryWrapper<CounselingSession>().eq(CounselingSession::getTenantId, t.getTenantId()));

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("tenantId", t.getTenantId());
            item.put("tenantCode", t.getTenantCode());
            item.put("tenantName", t.getTenantName());
            item.put("status", t.getStatus());
            item.put("createdAt", t.getCreatedAt());
            item.put("schoolCount", schools);
            item.put("studentCount", students);
            item.put("teacherCount", teachers);
            item.put("sessionCount", sessions);
            return item;
        }).collect(Collectors.toList());

        return ApiResponse.ok(result);
    }

    /** 单租户详情（学校列表 + 近 7 天会话趋势） */
    @GetMapping("/tenants/{tenantId}")
    public ApiResponse<Map<String, Object>> getTenantDetail(@PathVariable UUID tenantId) {
        Tenant tenant = tenantMapper.selectById(tenantId);
        if (tenant == null) {
            return ApiResponse.error(404, "租户不存在");
        }

        List<School> schools = schoolMapper.selectList(
                new LambdaQueryWrapper<School>().eq(School::getTenantId, tenantId));

        // 近 7 天每日会话数
        Instant weekAgo = Instant.now().minus(7, ChronoUnit.DAYS);
        List<CounselingSession> recentSessions = sessionMapper.selectList(
                new LambdaQueryWrapper<CounselingSession>()
                        .eq(CounselingSession::getTenantId, tenantId)
                        .ge(CounselingSession::getStartedAt, weekAgo));

        Map<String, Long> dailyTrend = recentSessions.stream()
                .filter(s -> s.getStartedAt() != null)
                .collect(Collectors.groupingBy(
                        s -> s.getStartedAt().atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString(),
                        Collectors.counting()));

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("tenant", tenant);
        detail.put("schools", schools);
        detail.put("dailySessionTrend", dailyTrend);
        return ApiResponse.ok(detail);
    }

    /** 学校列表（跨租户） */
    @GetMapping("/schools")
    public ApiResponse<List<School>> getSchools() {
        return ApiResponse.ok(schoolMapper.selectList(
                new LambdaQueryWrapper<School>().orderByDesc(School::getCreatedAt)));
    }
}
