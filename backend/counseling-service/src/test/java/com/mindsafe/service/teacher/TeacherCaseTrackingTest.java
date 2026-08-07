package com.mindsafe.service.teacher;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.entity.TeacherNote;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.*;
import com.mindsafe.service.security.FieldEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * TeacherService 个案跟踪标志与待办静音接线单测（F-3，design/35 §4.2 降噪机制）
 * <p>
 * 契约：
 * - setCaseTracking 落 teacher_notes（type=case_tracking，content=active/inactive），学生须存在且同租户
 * - isCaseTracking 取最新一条 case_tracking 备注，active 为 true
 * - getAlerts 返回 mutedFromTodo：个案跟踪中 + S2/S3（level≤1）→ true；S0/S1 永不静音
 */
class TeacherCaseTrackingTest {

    private RiskEventMapper riskEventMapper;
    private UserMapper userMapper;
    private TeacherNoteMapper teacherNoteMapper;
    private FieldEncryptionService fieldEncryptionService;
    private TeacherService teacherService;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID studentId = UUID.randomUUID();
    private final UUID teacherId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        riskEventMapper = mock(RiskEventMapper.class);
        userMapper = mock(UserMapper.class);
        teacherNoteMapper = mock(TeacherNoteMapper.class);
        fieldEncryptionService = mock(FieldEncryptionService.class);
        when(fieldEncryptionService.encrypt(any())).thenAnswer(inv -> inv.getArgument(0));
        when(fieldEncryptionService.decrypt(any())).thenAnswer(inv -> inv.getArgument(0));
        teacherService = new TeacherService(
                riskEventMapper,
                mock(CounselingSessionMapper.class),
                userMapper,
                teacherNoteMapper,
                mock(NotificationMapper.class),
                mock(MessageSummaryMapper.class),
                fieldEncryptionService);
    }

    private User givenStudent() {
        User student = new User();
        student.setUserId(studentId);
        student.setTenantId(tenantId);
        student.setPseudonym("小星");
        when(userMapper.selectById(studentId)).thenReturn(student);
        return student;
    }

    private TeacherNote caseTrackingNote(String content, Instant createdAt) {
        TeacherNote note = TeacherNote.create(tenantId, studentId, teacherId, content, "case_tracking");
        note.setCreatedAt(createdAt);
        return note;
    }

    // ===== setCaseTracking =====

    @Test
    void 开启个案跟踪_落active备注() {
        givenStudent();

        teacherService.setCaseTracking(tenantId, studentId, teacherId, true);

        ArgumentCaptor<TeacherNote> captor = ArgumentCaptor.forClass(TeacherNote.class);
        verify(teacherNoteMapper).insert(captor.capture());
        assertEquals("case_tracking", captor.getValue().getNoteType());
        assertEquals("active", captor.getValue().getContent());
        assertEquals(studentId, captor.getValue().getStudentUserId());
    }

    @Test
    void 关闭个案跟踪_落inactive备注() {
        givenStudent();

        teacherService.setCaseTracking(tenantId, studentId, teacherId, false);

        ArgumentCaptor<TeacherNote> captor = ArgumentCaptor.forClass(TeacherNote.class);
        verify(teacherNoteMapper).insert(captor.capture());
        assertEquals("inactive", captor.getValue().getContent());
    }

    @Test
    void 学生不存在_拒绝设置() {
        when(userMapper.selectById(studentId)).thenReturn(null);

        assertThrows(IllegalArgumentException.class,
                () -> teacherService.setCaseTracking(tenantId, studentId, teacherId, true));
        verify(teacherNoteMapper, never()).insert(any(TeacherNote.class));
    }

    @Test
    void 跨租户学生_拒绝设置() {
        User student = new User();
        student.setUserId(studentId);
        student.setTenantId(UUID.randomUUID());
        when(userMapper.selectById(studentId)).thenReturn(student);

        assertThrows(IllegalArgumentException.class,
                () -> teacherService.setCaseTracking(tenantId, studentId, teacherId, true));
    }

    // ===== isCaseTracking =====

    @Test
    void 最新备注为active_在跟踪中() {
        when(teacherNoteMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(caseTrackingNote("active", Instant.now())));

        assertTrue(teacherService.isCaseTracking(tenantId, studentId));
    }

    @Test
    void 最新备注为inactive_不在跟踪中() {
        when(teacherNoteMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(caseTrackingNote("inactive", Instant.now())));

        assertFalse(teacherService.isCaseTracking(tenantId, studentId));
    }

    @Test
    void 无备注_不在跟踪中() {
        when(teacherNoteMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        assertFalse(teacherService.isCaseTracking(tenantId, studentId));
    }

    // ===== getAlerts 静音接线 =====

    private RiskEvent givenAlert(int riskLevel) {
        RiskEvent event = RiskEvent.fromDetection(tenantId, studentId, UUID.randomUUID(), "self_harm", riskLevel);
        return event;
    }

    @Test
    void 个案跟踪中_S2预警_静音() {
        givenStudent();
        when(riskEventMapper.selectPage(any(), any())).thenReturn(new Page<RiskEvent>().setRecords(List.of(givenAlert(1))));
        when(teacherNoteMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(caseTrackingNote("active", Instant.now())));

        List<TeacherService.AlertVO> alerts = teacherService.getAlerts(tenantId, null, null, 50);

        assertEquals(1, alerts.size());
        assertTrue(alerts.get(0).mutedFromTodo());
    }

    @Test
    void 个案跟踪中_S1预警_不静音() {
        givenStudent();
        when(riskEventMapper.selectPage(any(), any())).thenReturn(new Page<RiskEvent>().setRecords(List.of(givenAlert(2))));
        when(teacherNoteMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(caseTrackingNote("active", Instant.now())));

        List<TeacherService.AlertVO> alerts = teacherService.getAlerts(tenantId, null, null, 50);

        assertFalse(alerts.get(0).mutedFromTodo());
    }

    @Test
    void 未跟踪_S2预警_不静音() {
        givenStudent();
        when(riskEventMapper.selectPage(any(), any())).thenReturn(new Page<RiskEvent>().setRecords(List.of(givenAlert(1))));
        when(teacherNoteMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        List<TeacherService.AlertVO> alerts = teacherService.getAlerts(tenantId, null, null, 50);

        assertFalse(alerts.get(0).mutedFromTodo());
    }

    @Test
    void 最新一条为inactive_不静音() {
        givenStudent();
        Instant base = Instant.now();
        when(riskEventMapper.selectPage(any(), any())).thenReturn(new Page<RiskEvent>().setRecords(List.of(givenAlert(0))));
        when(teacherNoteMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                caseTrackingNote("active", base.minusSeconds(60)),
                caseTrackingNote("inactive", base)
        ));

        List<TeacherService.AlertVO> alerts = teacherService.getAlerts(tenantId, null, null, 50);

        assertFalse(alerts.get(0).mutedFromTodo());
    }
}
