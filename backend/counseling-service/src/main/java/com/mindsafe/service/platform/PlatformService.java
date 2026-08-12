package com.mindsafe.service.platform;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
import com.mindsafe.service.common.CounselingTimeZone;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
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

        // P1-7（板块05）：每租户 4 次 selectCount 的 N+1 → GROUP BY 聚合一次取回
        //（对齐 TeacherService:726 情绪分布 selectMaps 范式），4 张表各 1 次聚合查询，
        // 循环内 getOrDefault 填充——租户数越多收益越显著
        Map<UUID, Long> schoolCounts = tenantCountMap(schoolMapper.selectMaps(
                new QueryWrapper<School>()
                        .select("tenant_id, COUNT(*) AS cnt")
                        .groupBy("tenant_id")));
        Map<UUID, Long> studentCounts = tenantCountMap(userMapper.selectMaps(
                new QueryWrapper<User>()
                        .select("tenant_id, COUNT(*) AS cnt")
                        .eq("user_type", User.USER_TYPE_STUDENT)
                        .groupBy("tenant_id")));
        Map<UUID, Long> teacherCounts = tenantCountMap(userMapper.selectMaps(
                new QueryWrapper<User>()
                        .select("tenant_id, COUNT(*) AS cnt")
                        .in("user_type", User.USER_TYPE_TEACHER, User.USER_TYPE_PSYCH_TEACHER,
                                User.USER_TYPE_CLASS_TEACHER, User.USER_TYPE_HEAD_TEACHER, User.USER_TYPE_ADMIN)
                        .groupBy("tenant_id")));
        Map<UUID, Long> sessionCounts = tenantCountMap(sessionMapper.selectMaps(
                new QueryWrapper<CounselingSession>()
                        .select("tenant_id, COUNT(*) AS cnt")
                        .groupBy("tenant_id")));

        return tenants.stream().map(t -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("tenantId", t.getTenantId());
            item.put("tenantCode", t.getTenantCode());
            item.put("tenantName", t.getTenantName());
            item.put("status", t.getStatus());
            item.put("createdAt", t.getCreatedAt());
            item.put("schoolCount", schoolCounts.getOrDefault(t.getTenantId(), 0L));
            item.put("studentCount", studentCounts.getOrDefault(t.getTenantId(), 0L));
            item.put("teacherCount", teacherCounts.getOrDefault(t.getTenantId(), 0L));
            item.put("sessionCount", sessionCounts.getOrDefault(t.getTenantId(), 0L));
            return item;
        }).collect(Collectors.toList());
        });
    }

    /**
     * P1-7：GROUP BY tenant_id 聚合行 → Map&lt;tenantId, count&gt;。
     * selectMaps 返回 key 为小写物理列名；DB 驱动可能返回 UUID 或字符串，兼容转换。
     */
    private static Map<UUID, Long> tenantCountMap(List<Map<String, Object>> rows) {
        Map<UUID, Long> result = new HashMap<>();
        if (rows == null) {
            return result;
        }
        for (Map<String, Object> row : rows) {
            Object tidVal = row.get("tenant_id");
            Object cntVal = row.get("cnt");
            if (tidVal == null || !(cntVal instanceof Number)) {
                continue;
            }
            UUID tid = tidVal instanceof UUID u ? u
                    : tidVal instanceof String s ? UUID.fromString(s) : null;
            if (tid != null) {
                result.put(tid, ((Number) cntVal).longValue());
            }
        }
        return result;
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

        // R-010（doing/92）：日桶收敛走 CounselingTimeZone.dateKey，原 systemDefault 依赖宿主机时区（跨部署漂移，G-P0-3）
        Map<String, Long> dailyTrend = recentSessions.stream()
                .filter(s -> s.getStartedAt() != null)
                .collect(Collectors.groupingBy(
                        s -> CounselingTimeZone.dateKey(s.getStartedAt()),
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
