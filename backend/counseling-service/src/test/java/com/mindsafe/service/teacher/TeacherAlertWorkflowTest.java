package com.mindsafe.service.teacher;

import com.mindsafe.service.conversation.MessageSummaryService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.entity.TeacherNote;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.*;
import com.mindsafe.service.audit.AuditLogService;
import com.mindsafe.service.security.FieldEncryptionService;
import com.mindsafe.service.session.SessionAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * TeacherService 预警状态机单测（TEST-007 覆盖补齐，design/16 §4 教师端 API）
 * <p>
 * 契约：
 * - claimAlert：open → claimed + assignedUserId
 * - markFalsePositive：status=false_positive + closedAt
 * - resolveAlert：resolved + resolutionNote + intervention 备注；空备注不落库
 * - scheduleFollowUp：follow_up_scheduled + followUpAt 解析 + followUpDone=false
 * - completeFollowUp：closed + followUpDone + outcome + follow_up 备注
 * - getPendingFollowUps：待回访列表透传
 * - 租户校验：跨租户/不存在 → IllegalArgumentException（防 IDOR）
 */
class TeacherAlertWorkflowTest {

    private RiskEventMapper riskEventMapper;
    private UserMapper userMapper;
    private TeacherNoteMapper teacherNoteMapper;
    private FieldEncryptionService fieldEncryptionService;
    private TeacherService teacherService;
    /** S-007②：直测预警生命周期状态机（行为断言迁移） */
    private AlertLifecycleService alertLifecycleService;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();
    private final UUID teacherUserId = UUID.randomUUID();
    private final UUID studentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        riskEventMapper = mock(RiskEventMapper.class);
        userMapper = mock(UserMapper.class);
        teacherNoteMapper = mock(TeacherNoteMapper.class);
        fieldEncryptionService = mock(FieldEncryptionService.class);
        when(fieldEncryptionService.encrypt(any())).thenAnswer(inv -> inv.getArgument(0));
        when(fieldEncryptionService.decrypt(any())).thenAnswer(inv -> inv.getArgument(0));
        alertLifecycleService = new AlertLifecycleService(
                riskEventMapper, new TeacherNoteStore(teacherNoteMapper), userMapper, fieldEncryptionService);
        teacherService = new TeacherService(
                riskEventMapper,
                mock(CounselingSessionMapper.class),
                userMapper,
                new TeacherNoteStore(teacherNoteMapper),
                mock(NotificationMapper.class),
                mock(MessageSummaryMapper.class),
                fieldEncryptionService,
                mock(SessionAccessService.class),
                mock(AuditLogService.class),
                new com.mindsafe.service.teacher.AlertTodoMutePolicy(),
                new com.mindsafe.service.casemanage.CaseLifecycleService(), mock(MessageSummaryService.class),
                mock(AlertLifecycleService.class),
                mock(TeacherDashboardService.class));
    }

    private RiskEvent givenEvent() {
        RiskEvent event = RiskEvent.fromDetection(tenantId, studentId, UUID.randomUUID(), "self_harm", 3);
        when(riskEventMapper.selectById(eventId)).thenReturn(event);
        return event;
    }

    @Test
    @DisplayName("认领：状态→claimed，指派教师")
    void claimAlert() {
        RiskEvent event = givenEvent();

        alertLifecycleService.claimAlert(tenantId, eventId, teacherUserId);

        assertEquals("claimed", event.getStatus());
        assertEquals(teacherUserId, event.getAssignedUserId());
        assertNotNull(event.getUpdatedAt());
        verify(riskEventMapper).updateById(event);
    }

    @Test
    @DisplayName("标记误报：status=false_positive + closedAt")
    void markFalsePositive() {
        RiskEvent event = givenEvent();

        alertLifecycleService.markFalsePositive(tenantId, eventId, teacherUserId);

        assertEquals("false_positive", event.getStatus());
        assertEquals(teacherUserId, event.getAssignedUserId());
        assertNotNull(event.getClosedAt());
        verify(riskEventMapper).updateById(event);
    }

    @Test
    @DisplayName("处理完成：resolved + 处理记录落 intervention 备注")
    void resolveAlert_withNote() {
        RiskEvent event = givenEvent();

        alertLifecycleService.resolveAlert(tenantId, eventId, teacherUserId, "已线下约谈家长");

        assertEquals("resolved", event.getStatus());
        assertEquals("已线下约谈家长", event.getResolutionNote());
        assertNotNull(event.getResolvedAt());
        assertNotNull(event.getClosedAt());
        ArgumentCaptor<TeacherNote> captor = ArgumentCaptor.forClass(TeacherNote.class);
        verify(teacherNoteMapper).insert(captor.capture());
        assertEquals("intervention", captor.getValue().getNoteType());
        assertTrue(captor.getValue().getContent().contains("已线下约谈家长"));
    }

    @Test
    @DisplayName("处理完成：空备注不落教师备注")
    void resolveAlert_withoutNote() {
        givenEvent();

        alertLifecycleService.resolveAlert(tenantId, eventId, teacherUserId, null);

        verify(teacherNoteMapper, never()).insert(any(TeacherNote.class));
    }

    @Test
    @DisplayName("安排回访：follow_up_scheduled + followUpAt 解析 + followUpDone=false")
    void scheduleFollowUp() {
        RiskEvent event = givenEvent();
        String followUpAtIso = "2026-08-01T10:00:00Z";

        alertLifecycleService.scheduleFollowUp(tenantId, eventId, teacherUserId, followUpAtIso);

        assertEquals("follow_up_scheduled", event.getStatus());
        assertEquals(Instant.parse(followUpAtIso), event.getFollowUpAt());
        assertFalse(event.getFollowUpDone());
        verify(riskEventMapper).updateById(event);
    }

    @Test
    @DisplayName("完成回访：closed + followUpDone + outcome + follow_up 备注")
    void completeFollowUp() {
        RiskEvent event = givenEvent();

        alertLifecycleService.completeFollowUp(tenantId, eventId, teacherUserId, "已恢复", "IMPROVED");

        assertEquals("closed", event.getStatus());
        assertTrue(event.getFollowUpDone());
        assertEquals("IMPROVED", event.getOutcome());
        ArgumentCaptor<TeacherNote> captor = ArgumentCaptor.forClass(TeacherNote.class);
        verify(teacherNoteMapper).insert(captor.capture());
        assertEquals("follow_up", captor.getValue().getNoteType());
    }

    @Test
    @DisplayName("待回访列表：透传 selectList 结果")
    void getPendingFollowUps() {
        RiskEvent pending = RiskEvent.fromDetection(tenantId, studentId, UUID.randomUUID(), "bullying", 2);
        pending.setFollowUpAt(Instant.now().plusSeconds(3600));
        pending.setFollowUpDone(false);
        when(riskEventMapper.selectList(any())).thenReturn(List.of(pending));

        List<RiskEvent> result = alertLifecycleService.getPendingFollowUps(tenantId);

        assertEquals(1, result.size());
        assertFalse(result.get(0).getFollowUpDone());
    }

    @Test
    @DisplayName("租户校验：跨租户预警 → 拒绝（防 IDOR）")
    void eventFromOtherTenant_rejected() {
        RiskEvent foreign = RiskEvent.fromDetection(UUID.randomUUID(), studentId, UUID.randomUUID(), "self_harm", 3);
        when(riskEventMapper.selectById(eventId)).thenReturn(foreign);

        assertThrows(BizException.class,
                () -> alertLifecycleService.claimAlert(tenantId, eventId, teacherUserId));
        verify(riskEventMapper, never()).updateById(any(RiskEvent.class));
    }

    @Test
    @DisplayName("租户校验：预警不存在 → 拒绝")
    void eventNotFound_rejected() {
        when(riskEventMapper.selectById(eventId)).thenReturn(null);

        assertThrows(BizException.class,
                () -> alertLifecycleService.resolveAlert(tenantId, eventId, teacherUserId, null));
    }

    @Test
    @DisplayName("getAlerts 四参重载：转发五参（全校范围）")
    void getAlertsFourArg_delegates() {
        RiskEvent event = givenEvent();
        when(riskEventMapper.selectPage(any(), any())).thenReturn(new Page<RiskEvent>().setRecords(List.of(event)));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of());

        List<TeacherService.AlertVO> alerts = teacherService.getAlerts(tenantId, "open", 2, 50);

        assertEquals(1, alerts.size());
        assertEquals("未知学生", alerts.get(0).studentName());
    }

    @Test
    @DisplayName("getAlerts：学生信息命中时映射实名")
    void getAlerts_studentNameMapped() {
        RiskEvent event = givenEvent();
        when(riskEventMapper.selectPage(any(), any())).thenReturn(new Page<RiskEvent>().setRecords(List.of(event)));
        User student = new User();
        student.setUserId(event.getStudentUserId());
        student.setPseudonym("小华*");
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(student));

        List<TeacherService.AlertVO> alerts = teacherService.getAlerts(tenantId, "open", 1, 20);

        assertEquals(1, alerts.size());
        assertEquals("小华*", alerts.get(0).studentName());
    }
}
