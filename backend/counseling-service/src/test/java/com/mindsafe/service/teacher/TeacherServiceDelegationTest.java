package com.mindsafe.service.teacher;

import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.domain.mapper.RiskEventMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * TeacherService 门面委托验证（S-007②，doing/93）。
 * <p>
 * 行为断言已迁移至 AlertLifecycleServiceTest（预警生命周期）与 TeacherDashboardServiceTest
 * （看板统计）；本测试验证门面透传——子域拆分后 Controller 侧 API 契约不变。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TeacherService 门面委托（S-007②）")
class TeacherServiceDelegationTest {

    @Mock private RiskEventMapper riskEventMapper;
    @Mock private AlertLifecycleService alertLifecycleService;
    @Mock private TeacherDashboardService dashboardService;

    private TeacherService teacherService;

    @BeforeEach
    void setUp() {
        teacherService = new TeacherService(
                riskEventMapper,
                mock(CounselingSessionMapper.class),
                mock(com.mindsafe.domain.mapper.UserMapper.class),
                mock(TeacherNoteStore.class),
                mock(com.mindsafe.domain.mapper.NotificationMapper.class),
                mock(com.mindsafe.domain.mapper.MessageSummaryMapper.class),
                mock(com.mindsafe.service.security.FieldEncryptionService.class),
                mock(com.mindsafe.service.session.SessionAccessService.class),
                mock(com.mindsafe.service.audit.AuditLogService.class),
                new com.mindsafe.service.teacher.AlertTodoMutePolicy(),
                new com.mindsafe.service.casemanage.CaseLifecycleService(),
                mock(com.mindsafe.service.conversation.MessageSummaryService.class),
                alertLifecycleService,
                dashboardService);
    }

    @Test
    @DisplayName("认领/处理/回访委托预警生命周期状态机")
    void lifecycleDelegates() {
        UUID t = UUID.randomUUID(), e = UUID.randomUUID(), u = UUID.randomUUID();
        teacherService.claimAlert(t, e, u);
        teacherService.resolveAlert(t, e, u, "note");
        teacherService.completeFollowUp(t, e, u, "note", "improved");

        verify(alertLifecycleService).claimAlert(t, e, u);
        verify(alertLifecycleService).resolveAlert(t, e, u, "note");
        verify(alertLifecycleService).completeFollowUp(t, e, u, "note", "improved");
    }

    @Test
    @DisplayName("看板概览委托统计子域（BACK-001：classScope null=全校）")
    void dashboardDelegates() {
        UUID t = UUID.randomUUID();
        teacherService.getDashboard(t, null);

        verify(dashboardService).getDashboard(t, null);
    }
}
