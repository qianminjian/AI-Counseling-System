package com.mindsafe.service.platform;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.entity.School;
import com.mindsafe.domain.entity.Tenant;
import com.mindsafe.domain.entity.User;
import com.mindsafe.common.tenant.TenantContextHolder;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.domain.mapper.RiskEventMapper;
import com.mindsafe.domain.mapper.SchoolMapper;
import com.mindsafe.domain.mapper.TenantMapper;
import com.mindsafe.domain.mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 平台管理聚合服务（SaaS 运营者视角）
 * <p>
 * C3（2026-08-05）：从 PlatformController 下沉的跨租户统计/聚合查询。
 * Controller 仅保留 HTTP 编排与参数绑定。
 */
@Service
public class PlatformService {

    private final TenantMapper tenantMapper;
    private final SchoolMapper schoolMapper;
    private final UserMapper userMapper;
    private final CounselingSessionMapper sessionMapper;
    private final RiskEventMapper riskEventMapper;

    public PlatformService(TenantMapper tenantMapper, SchoolMapper schoolMapper,
                           UserMapper userMapper, CounselingSessionMapper sessionMapper,
                           RiskEventMapper riskEventMapper) {
        this.tenantMapper = tenantMapper;
        this.schoolMapper = schoolMapper;
        this.userMapper = userMapper;
        this.sessionMapper = sessionMapper;
        this.riskEventMapper = riskEventMapper;
    }

    /** 平台总览（跨租户聚合） */
    public Map<String, Object> overview() {
        // 修复（2026-08-10）：跨租户聚合须 runAsSystem 声明系统作用域——
        // 原实现直接查租户表（schools/users/...）触发 M1-003 fail-fast → 平台总览 500
        return TenantContextHolder.callAsSystem(() -> {
        List<Tenant> tenants = tenantMapper.selectList(
                new LambdaQueryWrapper<Tenant>().eq(Tenant::getStatus, Tenant.STATUS_ACTIVE));

        long totalSchools = schoolMapper.selectCount(
                new LambdaQueryWrapper<School>().eq(School::getStatus, School.STATUS_ACTIVE));
        long totalStudents = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUserType, User.USER_TYPE_STUDENT).eq(User::getStatus, User.STATUS_ACTIVE));
        long totalTeachers = userMapper.selectCount(
                new LambdaQueryWrapper<User>()
                        .in(User::getUserType, User.USER_TYPE_TEACHER, User.USER_TYPE_PSYCH_TEACHER, User.USER_TYPE_CLASS_TEACHER, User.USER_TYPE_HEAD_TEACHER, User.USER_TYPE_ADMIN)
                        .eq(User::getStatus, User.STATUS_ACTIVE));
        long totalSessions = sessionMapper.selectCount(null);

        // 近 7 天活跃（有会话的学生数）
        Instant weekAgo = Instant.now().minus(7, ChronoUnit.DAYS);
        long weeklySessions = sessionMapper.selectCount(
                new LambdaQueryWrapper<CounselingSession>().ge(CounselingSession::getStartedAt, weekAgo));

        // 风险事件统计
        long totalAlerts = riskEventMapper.selectCount(null);
        long openAlerts = riskEventMapper.selectCount(
                new LambdaQueryWrapper<RiskEvent>().eq(RiskEvent::getStatus, RiskEvent.STATUS_OPEN));

        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("tenantCount", tenants.size());
        overview.put("schoolCount", totalSchools);
        overview.put("studentCount", totalStudents);
        overview.put("teacherCount", totalTeachers);
        overview.put("totalSessions", totalSessions);
        overview.put("weeklySessions", weeklySessions);
        overview.put("totalAlerts", totalAlerts);
        overview.put("openAlerts", openAlerts);
        return overview;
        });
    }

    /** 租户列表（含各校学生/教师数） */
    public List<Map<String, Object>> tenantStats() {
        // 修复（2026-08-10）：与 overview 同——跨租户聚合须 callAsSystem（M1-003 fail-fast）
        return TenantContextHolder.callAsSystem(() -> {
        List<Tenant> tenants = tenantMapper.selectList(
                new LambdaQueryWrapper<Tenant>().orderByDesc(Tenant::getCreatedAt));

        return tenants.stream().map(t -> {
            long schools = schoolMapper.selectCount(
                    new LambdaQueryWrapper<School>().eq(School::getTenantId, t.getTenantId()));
            long students = userMapper.selectCount(
                    new LambdaQueryWrapper<User>()
                            .eq(User::getTenantId, t.getTenantId())
                            .eq(User::getUserType, User.USER_TYPE_STUDENT));
            long teachers = userMapper.selectCount(
                    new LambdaQueryWrapper<User>()
                            .eq(User::getTenantId, t.getTenantId())
                            .in(User::getUserType, User.USER_TYPE_TEACHER, User.USER_TYPE_PSYCH_TEACHER, User.USER_TYPE_CLASS_TEACHER, User.USER_TYPE_HEAD_TEACHER, User.USER_TYPE_ADMIN));
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
        });
    }

    /** 单租户详情（学校列表 + 近 7 天会话趋势）；租户不存在返回 null */
    public Map<String, Object> tenantDetail(UUID tenantId) {
        Tenant tenant = tenantMapper.selectById(tenantId);
        if (tenant == null) {
            return null;
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
        return detail;
    }

    /** 学校列表（跨租户，按创建时间倒序） */
    public List<School> schools() {
        return schoolMapper.selectList(
                new LambdaQueryWrapper<School>().orderByDesc(School::getCreatedAt));
    }
}
