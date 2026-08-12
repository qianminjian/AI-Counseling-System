package com.mindsafe.service.teacher;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.domain.mapper.RiskEventMapper;
import com.mindsafe.service.common.CounselingTimeZone;
import com.mindsafe.service.teacher.TeacherService.DailyCount;
import com.mindsafe.service.teacher.TeacherService.DashboardVO;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 工作台看板统计（S-007②，doing/93）。
 * <p>
 * TeacherService 上帝类拆出的统计子域：工作台概览（待处理/今日新增/活跃会话/
 * 活跃学生/累计会话/周趋势/满意度）收敛于此。统计口径变更只改本组件；
 * 返回类型保持 TeacherService 门面 record（对外契约不变，Controller 零改动）。
 */
@Service
public class TeacherDashboardService {

    private final RiskEventMapper riskEventMapper;
    private final CounselingSessionMapper sessionMapper;

    public TeacherDashboardService(RiskEventMapper riskEventMapper, CounselingSessionMapper sessionMapper) {
        this.riskEventMapper = riskEventMapper;
        this.sessionMapper = sessionMapper;
    }

    /** 工作台概览（P1-FE-2 大屏卡片：待处理/今日新增/活跃会话/活跃学生/累计/周趋势/满意度） */
    public DashboardVO getDashboard(UUID tenantId) {
        Instant now = Instant.now();
        Instant todayStart = CounselingTimeZone.startOfDay(now); // B-03：上海日边界（UTC 截断会在 08:00 前漂移前一天）
        Instant weekAgo = now.minus(7, ChronoUnit.DAYS);

        // 待处理预警数
        long pendingAlerts = riskEventMapper.selectCount(
                new LambdaQueryWrapper<RiskEvent>()
                        .eq(RiskEvent::getTenantId, tenantId)
                        .eq(RiskEvent::getStatus, RiskEvent.STATUS_OPEN)
        );

        // 今日新增预警
        long todayAlerts = riskEventMapper.selectCount(
                new LambdaQueryWrapper<RiskEvent>()
                        .eq(RiskEvent::getTenantId, tenantId)
                        .ge(RiskEvent::getDetectedAt, todayStart)
        );

        // 今日活跃会话数
        long todaySessions = sessionMapper.selectCount(
                new LambdaQueryWrapper<CounselingSession>()
                        .eq(CounselingSession::getTenantId, tenantId)
                        .ge(CounselingSession::getStartedAt, todayStart)
        );

        // 今日活跃学生数（今日有会话的去重学生）：单次 DISTINCT 查询，避免全量会话查列表（P1-FE-2）
        List<Object> activeStudentIds = sessionMapper.selectObjs(
                new QueryWrapper<CounselingSession>()
                        .select("DISTINCT student_user_id")
                        .eq("tenant_id", tenantId)
                        .ge("started_at", todayStart));
        long activeStudents = activeStudentIds.stream().filter(Objects::nonNull).count();

        // 累计会话数（该租户全部会话，P1-FE-2 大屏"累计会话"卡片）
        long totalSessions = sessionMapper.selectCount(
                new LambdaQueryWrapper<CounselingSession>()
                        .eq(CounselingSession::getTenantId, tenantId)
        );

        // 周趋势（最近 7 天每天的风险事件数）：单次查询 + 内存分桶，替代 7 次循环 count
        Instant weekStart = CounselingTimeZone.truncateToDay(now.minus(6, ChronoUnit.DAYS));
        Instant tomorrowStart = CounselingTimeZone.startOfNextDay(now);
        List<RiskEvent> weekEvents = riskEventMapper.selectList(
                new LambdaQueryWrapper<RiskEvent>()
                        .eq(RiskEvent::getTenantId, tenantId)
                        .ge(RiskEvent::getDetectedAt, weekStart)
                        .lt(RiskEvent::getDetectedAt, tomorrowStart)
        );
        Map<Instant, Long> eventsByDay = weekEvents.stream()
                .map(RiskEvent::getDetectedAt)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(CounselingTimeZone::truncateToDay, Collectors.counting()));
        List<DailyCount> weeklyTrend = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            Instant dayStart = CounselingTimeZone.truncateToDay(now.minus(i, ChronoUnit.DAYS));
            weeklyTrend.add(new DailyCount(CounselingTimeZone.dateKey(dayStart),
                    eventsByDay.getOrDefault(dayStart, 0L)));
        }

        // 满意度统计（近 30 天有评价的会话）
        Instant monthAgo = now.minus(30, ChronoUnit.DAYS);
        List<CounselingSession> ratedSessions = sessionMapper.selectList(
                new LambdaQueryWrapper<CounselingSession>()
                        .eq(CounselingSession::getTenantId, tenantId)
                        .ge(CounselingSession::getStartedAt, monthAgo)
                        .isNotNull(CounselingSession::getSatisfactionRating)
        );
        double avgSatisfaction = ratedSessions.stream()
                .mapToInt(CounselingSession::getSatisfactionRating)
                .average().orElse(0.0);
        long satisfactionCount = ratedSessions.size();

        return new DashboardVO(pendingAlerts, todayAlerts, todaySessions, activeStudents, totalSessions,
                weeklyTrend, Math.round(avgSatisfaction * 10) / 10.0, satisfactionCount);
    }
}
