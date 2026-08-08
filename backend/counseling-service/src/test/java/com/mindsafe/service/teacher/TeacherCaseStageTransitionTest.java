package com.mindsafe.service.teacher;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.TeacherNote;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.*;
import com.mindsafe.service.casemanage.CaseLifecycleService;
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
 * TeacherService 个案阶段推进接线单测（P1 审计修复：transitionCase 伪 API 无持久化）。
 * <p>
 * 契约：
 * - getCurrentCaseStage 从 teacher_notes（note_type=case_stage）读取最新阶段，无记录 → INTAKE
 * - transitionCaseStage 校验学生存在且同租户，不可跳级（CaseLifecycleService 纯函数裁决）
 * - 允许推进时落 case_stage 备注（content=目标阶段名，加密），拒绝时不落库
 */
class TeacherCaseStageTransitionTest {

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
                fieldEncryptionService,
                mock(SessionAccessService.class),
                mock(AuditLogService.class));
    }

    private void givenStudent() {
        User student = new User();
        student.setUserId(studentId);
        student.setTenantId(tenantId);
        when(userMapper.selectById(studentId)).thenReturn(student);
    }

    private TeacherNote stageNote(String stage, Instant createdAt) {
        TeacherNote note = TeacherNote.create(tenantId, studentId, teacherId, stage, "case_stage");
        note.setCreatedAt(createdAt);
        return note;
    }

    // ===== getCurrentCaseStage =====

    @Test
    @DisplayName("无历史阶段 → 默认 INTAKE（建案）")
    void noHistory_defaultsToIntake() {
        when(teacherNoteMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        assertEquals(CaseLifecycleService.CaseStage.INTAKE,
                teacherService.getCurrentCaseStage(tenantId, studentId));
    }

    @Test
    @DisplayName("最新备注为 ASSESSMENT → 返回 ASSESSMENT")
    void latestNote_returnsStage() {
        when(teacherNoteMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                stageNote("INTAKE", Instant.now().minusSeconds(60)),
                stageNote("ASSESSMENT", Instant.now())
        ));

        assertEquals(CaseLifecycleService.CaseStage.ASSESSMENT,
                teacherService.getCurrentCaseStage(tenantId, studentId));
    }

    @Test
    @DisplayName("最新备注内容非法 → 忽略并按 INTAKE 处理")
    void invalidStageContent_fallsBackToIntake() {
        when(teacherNoteMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                stageNote("not_a_stage", Instant.now())));

        assertEquals(CaseLifecycleService.CaseStage.INTAKE,
                teacherService.getCurrentCaseStage(tenantId, studentId));
    }

    // ===== transitionCaseStage =====

    @Test
    @DisplayName("无历史（INTAKE）→ 推进 ASSESSMENT 允许并落 case_stage 备注")
    void intakeToAssessment_allowedAndPersisted() {
        givenStudent();
        when(teacherNoteMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        CaseLifecycleService.StageTransition result = teacherService.transitionCaseStage(
                tenantId, studentId, teacherId, CaseLifecycleService.CaseStage.ASSESSMENT);

        assertTrue(result.allowed());
        assertEquals(CaseLifecycleService.CaseStage.INTAKE, result.from());
        assertEquals(CaseLifecycleService.CaseStage.ASSESSMENT, result.to());

        ArgumentCaptor<TeacherNote> captor = ArgumentCaptor.forClass(TeacherNote.class);
        verify(teacherNoteMapper).insert(captor.capture());
        assertEquals("case_stage", captor.getValue().getNoteType());
        assertEquals("ASSESSMENT", captor.getValue().getContent());
        assertEquals(studentId, captor.getValue().getStudentUserId());
    }

    @Test
    @DisplayName("跳级推进（INTAKE→INTERVENTION）→ 拒绝且不持久化")
    void skipStage_rejectedWithoutPersistence() {
        givenStudent();
        when(teacherNoteMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        CaseLifecycleService.StageTransition result = teacherService.transitionCaseStage(
                tenantId, studentId, teacherId, CaseLifecycleService.CaseStage.INTERVENTION);

        assertFalse(result.allowed());
        verify(teacherNoteMapper, never()).insert(any(TeacherNote.class));
    }

    @Test
    @DisplayName("有历史（ASSESSMENT）→ 推进 INTERVENTION 允许，起点来自存储而非硬编码 INTAKE")
    void assessmentToIntervention_usesStoredStage() {
        givenStudent();
        when(teacherNoteMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                stageNote("INTAKE", Instant.now().minusSeconds(60)),
                stageNote("ASSESSMENT", Instant.now())
        ));

        CaseLifecycleService.StageTransition result = teacherService.transitionCaseStage(
                tenantId, studentId, teacherId, CaseLifecycleService.CaseStage.INTERVENTION);

        assertTrue(result.allowed());
        assertEquals(CaseLifecycleService.CaseStage.ASSESSMENT, result.from());
        verify(teacherNoteMapper).insert(any(TeacherNote.class));
    }

    @Test
    @DisplayName("学生不存在 → 拒绝推进")
    void studentNotFound_rejected() {
        when(userMapper.selectById(studentId)).thenReturn(null);

        assertThrows(BizException.class, () -> teacherService.transitionCaseStage(
                tenantId, studentId, teacherId, CaseLifecycleService.CaseStage.ASSESSMENT));
        verify(teacherNoteMapper, never()).insert(any(TeacherNote.class));
    }

    @Test
    @DisplayName("跨租户学生 → 拒绝推进")
    void crossTenantStudent_rejected() {
        User student = new User();
        student.setUserId(studentId);
        student.setTenantId(UUID.randomUUID());
        when(userMapper.selectById(studentId)).thenReturn(student);

        assertThrows(BizException.class, () -> teacherService.transitionCaseStage(
                tenantId, studentId, teacherId, CaseLifecycleService.CaseStage.ASSESSMENT));
    }

    @Test
    @DisplayName("CLOSED 无结案小结 → 拒绝（CaseLifecycleService 准入规则）")
    void closedWithoutSummary_rejected() {
        givenStudent();
        when(teacherNoteMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                stageNote("INTERVENTION", Instant.now())
        ));

        CaseLifecycleService.StageTransition result = teacherService.transitionCaseStage(
                tenantId, studentId, teacherId, CaseLifecycleService.CaseStage.CLOSED);

        assertFalse(result.allowed());
        assertNotNull(result.reason());
        verify(teacherNoteMapper, never()).insert(any(TeacherNote.class));
    }
}
