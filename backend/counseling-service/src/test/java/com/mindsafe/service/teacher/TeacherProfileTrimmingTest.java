package com.mindsafe.service.teacher;

import com.mindsafe.service.conversation.MessageSummaryService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.entity.TeacherNote;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.*;
import com.mindsafe.service.audit.AuditLogService;
import com.mindsafe.service.session.SessionAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * WB-002 学生详情页服务端角色裁剪测试（design/35 §3.3/§六）
 * <p>
 * 合规底线：权限裁剪必须在服务端完成——班主任请求档案时响应中
 * 根本不含风险轨迹（alertHistory/maxRiskLevel）与对话摘要（recentSessions）字段。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TeacherProfileTrimmingTest {

    @Mock private RiskEventMapper riskEventMapper;
    @Mock private CounselingSessionMapper sessionMapper;
    @Mock private UserMapper userMapper;
    @Mock private TeacherNoteMapper teacherNoteMapper;
    @Mock private NotificationMapper notificationMapper;
    @Mock private MessageSummaryMapper messageSummaryMapper;

    private TeacherService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID studentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new TeacherService(riskEventMapper, sessionMapper, userMapper,
                new TeacherNoteStore(teacherNoteMapper), notificationMapper, messageSummaryMapper,
                // R-01：未启用加密的真实加密服务 → 明文透传
                new com.mindsafe.service.security.FieldEncryptionService(
                        false, "", 1, "", new org.springframework.core.env.StandardEnvironment()),
                mock(SessionAccessService.class),
                mock(AuditLogService.class),
                new com.mindsafe.service.teacher.AlertTodoMutePolicy(),
                new com.mindsafe.service.casemanage.CaseLifecycleService(), mock(MessageSummaryService.class));

        User student = new User();
        student.setUserId(studentId);
        student.setTenantId(tenantId);
        student.setPseudonym("小明*");
        student.setGradeCode("G4");
        student.setClassCode("C1");
        when(userMapper.selectById(studentId)).thenReturn(student);

        TeacherNote note = new TeacherNote();
        note.setNoteId(UUID.randomUUID());
        note.setTeacherUserId(UUID.randomUUID());
        note.setContent("建议多关注");
        note.setNoteType("general");
        note.setCreatedAt(Instant.now());
        when(teacherNoteMapper.selectList(any())).thenReturn(List.of(note));
        // B5：isCaseTracking 改 selectPage（AUD-043）→ 默认非跟踪
        when(teacherNoteMapper.selectPage(any(), any())).thenReturn(new Page<TeacherNote>().setRecords(List.of()));

        CounselingSession session = new CounselingSession();
        session.setSessionId(UUID.randomUUID());
        session.setStartedAt(Instant.now());
        session.setSessionStatus("completed");
        session.setRiskLevelSnapshot(1);
        when(sessionMapper.selectPage(any(), any())).thenReturn(new Page<CounselingSession>().setRecords(List.of(session)));

        RiskEvent event = new RiskEvent();
        event.setRiskEventId(UUID.randomUUID());
        event.setStudentUserId(studentId);
        event.setRiskType("self_harm");
        event.setRiskLevel(2);
        event.setStatus("open");
        event.setDetectedAt(Instant.now());
        when(riskEventMapper.selectPage(any(), any())).thenReturn(new Page<RiskEvent>().setRecords(List.of(event)));
    }

    @Test
    @DisplayName("心理老师：完整档案（会话+预警+风险等级）")
    void psychTeacherFullAccess() {
        TeacherService.StudentProfileVO vo =
                service.getStudentProfile(tenantId, studentId, "psych_teacher");

        assertThat(vo.maxRiskLevel()).isEqualTo(2);
        assertThat(vo.recentSessions()).hasSize(1);
        assertThat(vo.alertHistory()).hasSize(1);
        assertThat(vo.notes()).hasSize(1);
    }

    @Test
    @DisplayName("班主任：风险轨迹与对话摘要字段为 null（服务端裁剪）")
    void classTeacherTrimmed() {
        TeacherService.StudentProfileVO vo =
                service.getStudentProfile(tenantId, studentId, "class_teacher");

        assertThat(vo.maxRiskLevel()).isNull();
        assertThat(vo.recentSessions()).isNull();
        assertThat(vo.alertHistory()).isNull();
        // 沟通建议（备注）仍可见
        assertThat(vo.notes()).hasSize(1);
        // 基本信息仍可见
        assertThat(vo.displayName()).isEqualTo("小明*");
    }

    @Test
    @DisplayName("班主任：不查询会话与预警表（隐私+效率）")
    void classTeacherSkipsSensitiveQueries() {
        service.getStudentProfile(tenantId, studentId, "class_teacher");

        verify(sessionMapper, never()).selectPage(any(), any());
        verify(riskEventMapper, never()).selectPage(any(), any());
    }

    @Test
    @DisplayName("管理员：完整档案")
    void adminFullAccess() {
        TeacherService.StudentProfileVO vo =
                service.getStudentProfile(tenantId, studentId, "admin");

        assertThat(vo.maxRiskLevel()).isEqualTo(2);
        assertThat(vo.alertHistory()).hasSize(1);
    }
}
