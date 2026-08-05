package com.mindsafe.service.teacher;

import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.MessageSummary;
import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.entity.TeacherNote;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.*;
import com.mindsafe.service.security.FieldEncryptionService;
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
 * TeacherService 学生档案/备注/对话摘要/高风险列表单测（TEST-007 覆盖补齐）
 * <p>
 * 契约：
 * - getStudentProfile 全量分支：maxRisk 取历史最高、会话摘要、预警历史、备注明文
 * - getHighRiskStudents：按学生分组取最高风险、降序、班级过滤、未知学生兜底
 * - addNote：加密落库、API 返回明文
 * - getSessionMessages：解密 + riskLevel 空值默认 0
 */
class TeacherArchiveNoteTest {

    private RiskEventMapper riskEventMapper;
    private CounselingSessionMapper sessionMapper;
    private UserMapper userMapper;
    private TeacherNoteMapper teacherNoteMapper;
    private MessageSummaryMapper messageSummaryMapper;
    private TeacherService teacherService;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID studentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        riskEventMapper = mock(RiskEventMapper.class);
        sessionMapper = mock(CounselingSessionMapper.class);
        userMapper = mock(UserMapper.class);
        teacherNoteMapper = mock(TeacherNoteMapper.class);
        messageSummaryMapper = mock(MessageSummaryMapper.class);
        FieldEncryptionService fieldEncryptionService = mock(FieldEncryptionService.class);
        when(fieldEncryptionService.encrypt(any())).thenAnswer(inv -> inv.getArgument(0));
        when(fieldEncryptionService.decrypt(any())).thenAnswer(inv -> inv.getArgument(0));
        teacherService = new TeacherService(
                riskEventMapper,
                sessionMapper,
                userMapper,
                teacherNoteMapper,
                mock(NotificationMapper.class),
                messageSummaryMapper,
                fieldEncryptionService);
    }

    private User givenStudent() {
        User student = new User();
        student.setUserId(studentId);
        student.setTenantId(tenantId);
        student.setPseudonym("小明*");
        student.setGradeCode("G4");
        student.setClassCode("C1");
        when(userMapper.selectById(studentId)).thenReturn(student);
        return student;
    }

    private RiskEvent event(int level) {
        RiskEvent e = RiskEvent.fromDetection(tenantId, studentId, UUID.randomUUID(), "self_harm", level);
        e.setStatus("open");
        e.setDetectedAt(Instant.now().minusSeconds(level));
        return e;
    }

    @Test
    @DisplayName("getStudentProfile 全量分支：maxRisk=历史最高，含会话摘要/预警历史/备注明文")
    void studentProfile_fullAccess() {
        givenStudent();

        TeacherNote note = new TeacherNote();
        note.setNoteId(UUID.randomUUID());
        note.setTeacherUserId(UUID.randomUUID());
        note.setContent("建议多关注");
        note.setNoteType("general");
        note.setCreatedAt(Instant.now());
        when(teacherNoteMapper.selectList(any())).thenReturn(List.of(note)); // 备注 + case_tracking 查询共用

        CounselingSession session = new CounselingSession();
        session.setSessionId(UUID.randomUUID());
        session.setStartedAt(Instant.now());
        session.setSessionStatus("completed");
        session.setRiskLevelSnapshot(2);
        session.setSatisfactionRating(4);
        when(sessionMapper.selectList(any())).thenReturn(List.of(session));

        // 历史预警 2 条：3 级 + 2 级 → maxRisk=3
        when(riskEventMapper.selectList(any())).thenReturn(List.of(event(2), event(3)));

        TeacherService.StudentProfileVO vo =
                teacherService.getStudentProfile(tenantId, studentId, "psych_teacher");

        assertEquals("小明*", vo.displayName());
        assertEquals(3, vo.maxRiskLevel());
        assertEquals(1, vo.totalSessions());
        assertEquals(1, vo.recentSessions().size());
        assertEquals(2, vo.alertHistory().size());
        assertEquals("建议多关注", vo.notes().get(0).content());
        // 预警历史 mutedFromTodo：level=3 且未个案跟踪 → 不静音（AlertTodoMutePolicy）
        assertFalse(vo.alertHistory().stream().allMatch(TeacherService.AlertVO::mutedFromTodo));
    }

    @Test
    @DisplayName("getStudentProfile：学生不存在或跨租户 → 拒绝")
    void studentProfile_studentNotFound() {
        when(userMapper.selectById(studentId)).thenReturn(null);
        assertThrows(IllegalArgumentException.class,
                () -> teacherService.getStudentProfile(tenantId, studentId, "teacher"));

        User foreign = new User();
        foreign.setUserId(studentId);
        foreign.setTenantId(UUID.randomUUID());
        when(userMapper.selectById(studentId)).thenReturn(foreign);
        assertThrows(IllegalArgumentException.class,
                () -> teacherService.getStudentProfile(tenantId, studentId, "teacher"));
    }

    @Test
    @DisplayName("getHighRiskStudents：按学生分组取最高风险，降序排列")
    void highRiskStudents_groupedAndSorted() {
        UUID studentA = UUID.randomUUID();
        UUID studentB = UUID.randomUUID();
        RiskEvent a1 = RiskEvent.fromDetection(tenantId, studentA, UUID.randomUUID(), "self_harm", 3);
        RiskEvent a2 = RiskEvent.fromDetection(tenantId, studentA, UUID.randomUUID(), "bullying", 2);
        RiskEvent b1 = RiskEvent.fromDetection(tenantId, studentB, UUID.randomUUID(), "anxiety", 1);
        a1.setDetectedAt(Instant.now().minusSeconds(10));
        a2.setDetectedAt(Instant.now().minusSeconds(20));
        b1.setDetectedAt(Instant.now().minusSeconds(5));
        when(riskEventMapper.selectList(any())).thenReturn(List.of(a1, a2, b1));

        User userA = new User();
        userA.setUserId(studentA);
        userA.setPseudonym("学生A");
        userA.setGradeCode("G5");
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(userA));

        List<TeacherService.HighRiskStudentVO> result = teacherService.getHighRiskStudents(tenantId, null);

        assertEquals(2, result.size());
        assertEquals(studentA, result.get(0).studentUserId()); // 最高风险 3 在前
        assertEquals(3, result.get(0).maxRiskLevel());
        assertEquals(2, result.get(0).openAlertCount());
        assertEquals("学生A", result.get(0).displayName());
        assertEquals(studentB, result.get(1).studentUserId());
        assertEquals("未知", result.get(1).displayName()); // 无学生信息兜底
    }

    @Test
    @DisplayName("getHighRiskStudents：班级过滤只保留本班学生")
    void highRiskStudents_classScopeFilter() {
        UUID studentA = UUID.randomUUID();
        UUID studentB = UUID.randomUUID();
        RiskEvent a1 = RiskEvent.fromDetection(tenantId, studentA, UUID.randomUUID(), "self_harm", 3);
        RiskEvent b1 = RiskEvent.fromDetection(tenantId, studentB, UUID.randomUUID(), "anxiety", 2);
        when(riskEventMapper.selectList(any())).thenReturn(List.of(a1, b1));

        User classA = new User();
        classA.setUserId(studentA);
        classA.setUserType("student");
        classA.setClassCode("C1");
        when(userMapper.selectList(any())).thenReturn(List.of(classA)); // 本班学生
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(classA));

        List<TeacherService.HighRiskStudentVO> result =
                teacherService.getHighRiskStudents(tenantId, "C1");

        assertEquals(1, result.size());
        assertEquals(studentA, result.get(0).studentUserId());
    }

    @Test
    @DisplayName("addNote：加密落库，API 返回明文")
    void addNote_encryptsAndReturnsPlain() {
        givenStudent();
        String noteId = UUID.randomUUID().toString();
        when(teacherNoteMapper.insert(any(TeacherNote.class))).thenAnswer(inv -> {
            TeacherNote n = inv.getArgument(0);
            n.setNoteId(UUID.fromString(noteId));
            return 1;
        });

        TeacherNote result = teacherService.addNote(tenantId, studentId, UUID.randomUUID(), "多鼓励", "general");

        ArgumentCaptor<TeacherNote> captor = ArgumentCaptor.forClass(TeacherNote.class);
        verify(teacherNoteMapper).insert(captor.capture());
        assertEquals("多鼓励", captor.getValue().getContent()); // 加密前内容
        assertEquals("多鼓励", result.getContent());           // 返回明文
        assertEquals("general", result.getNoteType());
    }

    @Test
    @DisplayName("getSessionMessages：解密 + riskLevel 空值默认 0")
    void getSessionMessages_decrypts() {
        MessageSummary summary = new MessageSummary();
        summary.setSummaryId(UUID.randomUUID());
        summary.setSenderType("student");
        summary.setTurnCount(3);
        summary.setContentSummary("我今天有点难过");
        summary.setEmotionLabel("sad");
        summary.setRiskLevel(2);
        summary.setCreatedAt(Instant.now());
        MessageSummary noRisk = new MessageSummary();
        noRisk.setSummaryId(UUID.randomUUID());
        noRisk.setSenderType("ai");
        noRisk.setTurnCount(4);
        noRisk.setContentSummary("要跟老师说哦");
        noRisk.setCreatedAt(Instant.now());
        when(messageSummaryMapper.selectList(any())).thenReturn(List.of(summary, noRisk));

        List<TeacherService.MessageSummaryVO> result =
                teacherService.getSessionMessages(tenantId, UUID.randomUUID());

        assertEquals(2, result.size());
        assertEquals("我今天有点难过", result.get(0).contentSummary());
        assertEquals(2, result.get(0).riskLevel());
        assertEquals(0, result.get(1).riskLevel()); // null → 0
    }
}
