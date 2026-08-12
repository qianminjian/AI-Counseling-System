package com.mindsafe.service.teacher;

import com.mindsafe.service.conversation.MessageSummaryService;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.entity.TeacherNote;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.*;
import com.mindsafe.service.audit.AuditLogService;
import com.mindsafe.service.security.FieldEncryptionService;
import com.mindsafe.service.session.SessionAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * TeacherService.transferAlert 单测（F-3，design/35 §4.1 转派规则）
 * <p>
 * 契约：
 * - 转派后预警重置为 open（目标教师的"新预警"），assignedUserId=目标教师
 * - 转派重置认领但不重置 SLA（detectedAt 不变）
 * - 带备注时落教师备注（noteType=transfer）
 * - 目标教师不存在或跨租户 → IllegalArgumentException
 */
class TeacherAlertTransferTest {

    private RiskEventMapper riskEventMapper;
    private UserMapper userMapper;
    private TeacherNoteMapper teacherNoteMapper;
    private FieldEncryptionService fieldEncryptionService;
    private TeacherService teacherService;
    /** S-007②：直测预警生命周期状态机（行为断言迁移） */
    private AlertLifecycleService alertLifecycleService;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();
    private final UUID fromTeacherId = UUID.randomUUID();
    private final UUID targetTeacherId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        riskEventMapper = mock(RiskEventMapper.class);
        userMapper = mock(UserMapper.class);
        teacherNoteMapper = mock(TeacherNoteMapper.class);
        fieldEncryptionService = mock(FieldEncryptionService.class);
        when(fieldEncryptionService.encrypt(any())).thenAnswer(inv -> inv.getArgument(0));
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

    private RiskEvent givenOpenEvent() {
        RiskEvent event = RiskEvent.fromDetection(tenantId, UUID.randomUUID(), UUID.randomUUID(), "self_harm", 3);
        event.setStatus("claimed");
        event.setAssignedUserId(fromTeacherId);
        when(riskEventMapper.selectById(eventId)).thenReturn(event);
        return event;
    }

    private User givenTargetTeacher(UUID targetTenantId) {
        User target = new User();
        target.setUserId(targetTeacherId);
        target.setTenantId(targetTenantId);
        when(userMapper.selectById(targetTeacherId)).thenReturn(target);
        return target;
    }

    @Test
    void 转派重置认领_状态回到open_指派目标教师() {
        RiskEvent event = givenOpenEvent();
        givenTargetTeacher(tenantId);

        alertLifecycleService.transferAlert(tenantId, eventId, fromTeacherId, targetTeacherId, "请心理老师跟进");

        ArgumentCaptor<RiskEvent> captor = ArgumentCaptor.forClass(RiskEvent.class);
        verify(riskEventMapper).updateById(captor.capture());
        RiskEvent updated = captor.getValue();
        assertEquals("open", updated.getStatus());
        assertEquals(targetTeacherId, updated.getAssignedUserId());
        // 不重置 SLA：检测时间不变
        assertEquals(event.getDetectedAt(), updated.getDetectedAt());
    }

    @Test
    void 带备注时落教师备注_transfer类型() {
        givenOpenEvent();
        givenTargetTeacher(tenantId);

        alertLifecycleService.transferAlert(tenantId, eventId, fromTeacherId, targetTeacherId, "请心理老师跟进");

        ArgumentCaptor<TeacherNote> captor = ArgumentCaptor.forClass(TeacherNote.class);
        verify(teacherNoteMapper).insert(captor.capture());
        assertEquals("transfer", captor.getValue().getNoteType());
        assertTrue(captor.getValue().getContent().contains("请心理老师跟进"));
    }

    @Test
    void 无备注不落教师备注() {
        givenOpenEvent();
        givenTargetTeacher(tenantId);

        alertLifecycleService.transferAlert(tenantId, eventId, fromTeacherId, targetTeacherId, null);

        verify(teacherNoteMapper, never()).insert(any(TeacherNote.class));
    }

    @Test
    void 目标教师不存在_拒绝转派() {
        givenOpenEvent();
        when(userMapper.selectById(targetTeacherId)).thenReturn(null);

        assertThrows(BizException.class,
                () -> alertLifecycleService.transferAlert(tenantId, eventId, fromTeacherId, targetTeacherId, null));
        verify(riskEventMapper, never()).updateById(any(RiskEvent.class));
    }

    @Test
    void 目标教师跨租户_拒绝转派() {
        givenOpenEvent();
        givenTargetTeacher(UUID.randomUUID()); // 其他租户

        assertThrows(BizException.class,
                () -> alertLifecycleService.transferAlert(tenantId, eventId, fromTeacherId, targetTeacherId, null));
        verify(riskEventMapper, never()).updateById(any(RiskEvent.class));
    }

    @Test
    void 预警不存在_拒绝转派() {
        when(riskEventMapper.selectById(eventId)).thenReturn(null);

        assertThrows(BizException.class,
                () -> alertLifecycleService.transferAlert(tenantId, eventId, fromTeacherId, targetTeacherId, null));
    }
}
