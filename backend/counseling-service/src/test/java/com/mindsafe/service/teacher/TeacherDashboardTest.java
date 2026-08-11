package com.mindsafe.service.teacher;

import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.mapper.*;
import com.mindsafe.service.audit.AuditLogService;
import com.mindsafe.service.security.FieldEncryptionService;
import com.mindsafe.service.session.SessionAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TeacherService.getDashboard 统计正确性单测（P1-FE-2：大屏恒 0 根因修复）
 * <p>
 * 契约：
 * - activeStudents = 今日有会话的去重学生数（DISTINCT 查询 + Java 侧 null 过滤）
 * - totalSessions = 租户累计会话数
 * - pendingAlerts / todayAlerts / todaySessions / 满意度 与既有逻辑保持一致
 */
class TeacherDashboardTest {

    private RiskEventMapper riskEventMapper;
    private CounselingSessionMapper sessionMapper;
    private TeacherService teacherService;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID teacherUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        riskEventMapper = mock(RiskEventMapper.class);
        sessionMapper = mock(CounselingSessionMapper.class);
        teacherService = new TeacherService(
                riskEventMapper,
                sessionMapper,
                mock(UserMapper.class),
                mock(TeacherNoteMapper.class),
                mock(NotificationMapper.class),
                mock(MessageSummaryMapper.class),
                mock(FieldEncryptionService.class),
                mock(SessionAccessService.class),
                mock(AuditLogService.class),
                new com.mindsafe.service.teacher.AlertTodoMutePolicy(),
                new com.mindsafe.service.casemanage.CaseLifecycleService());
    }

    private CounselingSession ratedSession(int rating) {
        CounselingSession s = new CounselingSession();
        s.setTenantId(tenantId);
        s.setStudentUserId(UUID.randomUUID());
        s.setStartedAt(Instant.now());
        s.setSatisfactionRating(rating);
        return s;
    }

    @Test
    @DisplayName("全量统计：新字段 activeStudents/totalSessions 计算正确，既有字段不受影响")
    void fullStats() {
        // selectCount 调用顺序：pendingAlerts(2) → todayAlerts(5)
        when(riskEventMapper.selectCount(any())).thenReturn(2L, 5L);
        // 周趋势 2 个风险事件
        when(riskEventMapper.selectList(any())).thenReturn(List.of(
                RiskEvent.fromDetection(tenantId, UUID.randomUUID(), UUID.randomUUID(), "self_harm", 3),
                RiskEvent.fromDetection(tenantId, UUID.randomUUID(), UUID.randomUUID(), "bullying", 2)));

        // selectCount 调用顺序：todaySessions(3) → totalSessions(50)
        when(sessionMapper.selectCount(any())).thenReturn(3L, 50L);
        // 今日活跃学生（DISTINCT 已由 SQL 去重）
        when(sessionMapper.selectObjs(any())).thenReturn(List.of(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        // 近 30 天有评价会话 2 条（4★、5★）
        when(sessionMapper.selectList(any())).thenReturn(List.of(ratedSession(4), ratedSession(5)));

        TeacherService.DashboardVO vo = teacherService.getDashboard(tenantId, teacherUserId);

        assertEquals(2L, vo.pendingAlerts());
        assertEquals(5L, vo.todayAlerts());
        assertEquals(3L, vo.todaySessions());
        assertEquals(3L, vo.activeStudents());
        assertEquals(50L, vo.totalSessions());
        assertEquals(4.5, vo.avgSatisfaction());
        assertEquals(2L, vo.satisfactionCount());
        // 周趋势恒为 7 天（含无事件日补 0）
        assertEquals(7, vo.weeklyTrend().size());
    }

    @Test
    @DisplayName("空数据：新字段与既有字段全部为 0，不抛异常")
    void emptyData() {
        when(riskEventMapper.selectList(any())).thenReturn(List.of());
        when(sessionMapper.selectObjs(any())).thenReturn(List.of());
        when(sessionMapper.selectList(any())).thenReturn(List.of());

        TeacherService.DashboardVO vo = teacherService.getDashboard(tenantId, teacherUserId);

        assertEquals(0L, vo.pendingAlerts());
        assertEquals(0L, vo.todayAlerts());
        assertEquals(0L, vo.todaySessions());
        assertEquals(0L, vo.activeStudents());
        assertEquals(0L, vo.totalSessions());
        assertEquals(0.0, vo.avgSatisfaction());
        assertEquals(0L, vo.satisfactionCount());
        assertEquals(7, vo.weeklyTrend().size());
    }

    @Test
    @DisplayName("activeStudents：查询结果含 null 时过滤后计数")
    void activeStudentsFiltersNull() {
        // List.of 不允许 null 元素，用 Arrays.asList
        when(sessionMapper.selectObjs(any())).thenReturn(Arrays.asList(UUID.randomUUID(), null, UUID.randomUUID()));

        TeacherService.DashboardVO vo = teacherService.getDashboard(tenantId, teacherUserId);

        assertEquals(2L, vo.activeStudents());
    }
}
