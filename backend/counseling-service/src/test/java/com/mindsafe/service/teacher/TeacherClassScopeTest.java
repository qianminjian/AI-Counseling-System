package com.mindsafe.service.teacher;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.entity.TeacherNote;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.*;
import com.mindsafe.service.audit.AuditLogService;
import com.mindsafe.service.security.FieldEncryptionService;
import com.mindsafe.service.session.SessionAccessService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.ArgumentCaptor;

/**
 * TeacherService 数据范围解析单测（P1 审计修复：班主任无 classCode 不再全校可见兜底）。
 * <p>
 * 契约：
 * - resolveClassScope：admin/psych_teacher → null（全校）；班主任有班级 → classCode；
 *   班主任无班级（或查不到）→ 空串（无可见范围）
 * - getAlerts 支持 classScope 过滤：本班学生事件保留、他班剔除；空班级/未绑定 → 空列表
 * - getStats 空范围 → 空统计 VO（不再全校聚合）
 */
class TeacherClassScopeTest {

    private RiskEventMapper riskEventMapper;
    private UserMapper userMapper;
    private TeacherNoteMapper teacherNoteMapper;
    private MessageSummaryMapper messageSummaryMapper;
    private SessionAccessService sessionAccessService;
    private CounselingSessionMapper sessionMapper;
    private TeacherService teacherService;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID teacherId = UUID.randomUUID();

    @BeforeAll
    static void initTableInfo() {
        // lambda wrapper 断言需要 MyBatis-Plus 实体元数据缓存（同 PlatformServiceTest 先例）
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), User.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), RiskEvent.class);
    }

    @BeforeEach
    void setUp() {
        riskEventMapper = mock(RiskEventMapper.class);
        userMapper = mock(UserMapper.class);
        teacherNoteMapper = mock(TeacherNoteMapper.class);
        messageSummaryMapper = mock(MessageSummaryMapper.class);
        FieldEncryptionService fieldEncryptionService = mock(FieldEncryptionService.class);
        sessionAccessService = mock(SessionAccessService.class);
        sessionMapper = mock(CounselingSessionMapper.class);
        teacherService = new TeacherService(
                riskEventMapper,
                sessionMapper,
                userMapper,
                teacherNoteMapper,
                mock(NotificationMapper.class),
                messageSummaryMapper,
                fieldEncryptionService,
                sessionAccessService,
                mock(AuditLogService.class));
    }

    private User teacherWithClass(String classCode) {
        User teacher = new User();
        teacher.setUserId(teacherId);
        teacher.setTenantId(tenantId);
        teacher.setClassCode(classCode);
        when(userMapper.selectById(teacherId)).thenReturn(teacher);
        return teacher;
    }

    // ===== resolveClassScope =====

    @Test
    @DisplayName("班主任绑定班级 → 返回班级代码")
    void classTeacher_withClass_returnsClassCode() {
        teacherWithClass("CLASS_1");
        assertEquals("CLASS_1", teacherService.resolveClassScope(tenantId, teacherId, "class_teacher"));
    }

    @Test
    @DisplayName("班主任未绑定班级（null）→ 返回空串（无可见范围，而非全校）")
    void classTeacher_withoutClass_returnsEmpty() {
        teacherWithClass(null);
        assertEquals("", teacherService.resolveClassScope(tenantId, teacherId, "class_teacher"));
    }

    @Test
    @DisplayName("班主任未绑定班级（空白）→ 返回空串")
    void classTeacher_blankClass_returnsEmpty() {
        teacherWithClass("  ");
        assertEquals("", teacherService.resolveClassScope(tenantId, teacherId, "class_teacher"));
    }

    @Test
    @DisplayName("班主任查无此人 → 返回空串（防越权兜底）")
    void classTeacher_notFound_returnsEmpty() {
        when(userMapper.selectById(teacherId)).thenReturn(null);
        assertEquals("", teacherService.resolveClassScope(tenantId, teacherId, "class_teacher"));
    }

    @Test
    @DisplayName("admin → 全校（null）")
    void admin_returnsNull() {
        assertEquals(null, teacherService.resolveClassScope(tenantId, teacherId, "admin"));
    }

    @Test
    @DisplayName("psych_teacher → 全校（null）")
    void psychTeacher_returnsNull() {
        assertEquals(null, teacherService.resolveClassScope(tenantId, teacherId, "psych_teacher"));
    }

    // ===== getAlerts 班级过滤 =====

    private User student(UUID id, String classCode) {
        User s = new User();
        s.setUserId(id);
        s.setTenantId(tenantId);
        s.setUserType("student");
        s.setClassCode(classCode);
        return s;
    }

    private RiskEvent alert(UUID studentId) {
        return RiskEvent.fromDetection(tenantId, studentId, UUID.randomUUID(), "self_harm", 3);
    }

    @Test
    @DisplayName("getAlerts 带 classScope → 仅保留本班学生事件")
    void getAlerts_withScope_filtersByClass() {
        UUID inClass = UUID.randomUUID();
        UUID otherClass = UUID.randomUUID();
        // 班级学生查询（DB 已按 classCode 过滤，B5 下沉 SessionAccessService）→ 仅本班学生；事件列表含他班 → 内存过滤剔除
        when(sessionAccessService.listClassStudents(tenantId, "CLASS_1")).thenReturn(List.of(student(inClass, "CLASS_1")));
        when(riskEventMapper.selectPage(any(), any())).thenReturn(new Page<RiskEvent>().setRecords(
                List.of(alert(inClass), alert(otherClass))));
        when(teacherNoteMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        List<TeacherService.AlertVO> alerts = teacherService.getAlerts(tenantId, "CLASS_1", null, null, 50);

        assertEquals(1, alerts.size());
        assertEquals(inClass, alerts.get(0).studentUserId());
    }

    @Test
    @DisplayName("getAlerts 空班级（无学生）→ 空列表（不全校兜底）")
    void getAlerts_emptyClass_returnsEmpty() {
        when(sessionAccessService.listClassStudents(tenantId, "CLASS_EMPTY")).thenReturn(List.of());

        List<TeacherService.AlertVO> alerts = teacherService.getAlerts(tenantId, "CLASS_EMPTY", null, null, 50);

        assertTrue(alerts.isEmpty());
    }

    @Test
    @DisplayName("getAlerts 未绑定班级（空串）→ 空列表")
    void getAlerts_blankScope_returnsEmpty() {
        when(sessionAccessService.listClassStudents(tenantId, "")).thenReturn(List.of());

        List<TeacherService.AlertVO> alerts = teacherService.getAlerts(tenantId, "", null, null, 50);

        assertTrue(alerts.isEmpty());
    }

    @Test
    @DisplayName("getAlerts 无 scope（心理老师）→ 全校事件")
    void getAlerts_noScope_returnsAll() {
        UUID s1 = UUID.randomUUID();
        when(riskEventMapper.selectPage(any(), any())).thenReturn(new Page<RiskEvent>().setRecords(List.of(alert(s1))));
        when(teacherNoteMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        List<TeacherService.AlertVO> alerts = teacherService.getAlerts(tenantId, null, null, null, 50);

        assertEquals(1, alerts.size());
    }

    // ===== listActiveStudents / getStats 班级范围（BA-02：导出/周报越权收敛） =====

    @Test
    @DisplayName("listActiveStudents 带 classScope → 查询条件限定本班（导出学生仅本班）")
    void listActiveStudents_withScope_filtersByClass() {
        User inClass = student(UUID.randomUUID(), "CLASS_1");
        when(userMapper.selectList(any(Wrapper.class))).thenReturn(List.of(inClass));

        List<User> students = teacherService.listActiveStudents(tenantId, "CLASS_1");

        ArgumentCaptor<LambdaQueryWrapper<User>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(userMapper).selectList(captor.capture());
        captor.getValue().getSqlSegment(); // 触发条件参数惰性初始化（MyBatis-Plus 延迟拼参）
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue("CLASS_1"),
                "classScope 应作为 classCode 条件传入查询");
        assertEquals(1, students.size());
        assertEquals(inClass, students.get(0));
    }

    @Test
    @DisplayName("getStats 带 classScope → 风险事件查询 IN 条件仅含本班学生（周报仅本班统计）")
    void getStats_withScope_restrictsEventsToClass() {
        UUID inClass = UUID.randomUUID();
        UUID otherClass = UUID.randomUUID();
        when(sessionAccessService.listClassStudents(tenantId, "CLASS_1"))
                .thenReturn(List.of(student(inClass, "CLASS_1"), student(otherClass, "CLASS_2")));
        when(userMapper.selectList(any(Wrapper.class))).thenReturn(List.of(student(inClass, "CLASS_1")));
        when(riskEventMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(sessionMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(messageSummaryMapper.selectMaps(any(Wrapper.class))).thenReturn(List.of());

        TeacherService.StatsVO stats = teacherService.getStats(tenantId, "CLASS_1");

        ArgumentCaptor<LambdaQueryWrapper<RiskEvent>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(riskEventMapper).selectList(captor.capture());
        captor.getValue().getSqlSegment(); // 触发条件参数惰性初始化（MyBatis-Plus 延迟拼参）
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue(inClass),
                "事件查询应 IN 限定为本班学生（不含他班）");
        assertEquals(1, stats.classComparison().size());
        assertEquals("CLASS_1", stats.classComparison().get(0).classCode());
    }

    @Test
    @DisplayName("listActiveStudents 未绑定班级（空串）→ 查询条件限定空班级（导出空结果，不全校）")
    void listActiveStudents_blankScope_restrictsEmptyClass() {
        when(userMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        List<User> students = teacherService.listActiveStudents(tenantId, "");

        ArgumentCaptor<LambdaQueryWrapper<User>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(userMapper).selectList(captor.capture());
        captor.getValue().getSqlSegment(); // 触发条件参数惰性初始化（MyBatis-Plus 延迟拼参）
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue(""),
                "空 scope 应作为 classCode 条件传入（SQL 查空班级 → 空结果）");
        assertTrue(students.isEmpty());
    }

    // ===== getStats 空范围 =====

    @Test
    @DisplayName("getStats 空班级范围 → 空统计（不聚合全校数据）")
    void getStats_emptyScope_returnsEmptyVO() {
        // B5：班级范围查询下沉 SessionAccessService；班级对比复用 listActiveStudents（仍走 userMapper）
        when(sessionAccessService.listClassStudents(tenantId, "")).thenReturn(List.of());
        when(userMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        TeacherService.StatsVO stats = teacherService.getStats(tenantId, "");

        assertTrue(stats.riskDistribution().isEmpty());
        assertTrue(stats.classComparison().isEmpty());
    }
}
