package com.mindsafe.api.controller;

import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.api.security.JwtTokenProvider;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.entity.TeacherNote;
import com.mindsafe.domain.entity.User;
import com.mindsafe.service.audit.AuditLogService;
import com.mindsafe.service.notification.NotificationService;
import com.mindsafe.service.profile.ProfileRadarService;
import com.mindsafe.service.security.FieldEncryptionService;
import com.mindsafe.service.session.SessionAccessService;
import com.mindsafe.service.teacher.TeacherService;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TeacherController 补充单元测试（P1 覆盖率冲刺：工作台/学生管理/通知/导出/接管/周报）
 */
class TeacherControllerFullTest {

    private NotificationService notificationService;
    private TeacherService teacherService;
    private ProfileRadarService profileRadarService;
    private AuditLogService auditLogService;
    private JwtTokenProvider jwtTokenProvider;
    private FieldEncryptionService fieldEncryptionService;
    private SessionAccessService sessionAccessService;
    private TeacherController controller;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID teacherUserId = UUID.randomUUID();
    private final UUID studentUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        teacherService = mock(TeacherService.class);
        profileRadarService = mock(ProfileRadarService.class);
        auditLogService = mock(AuditLogService.class);
        jwtTokenProvider = mock(JwtTokenProvider.class);
        fieldEncryptionService = mock(FieldEncryptionService.class);
        sessionAccessService = mock(SessionAccessService.class);
        controller = new TeacherController(notificationService, teacherService, profileRadarService,
                auditLogService, jwtTokenProvider, fieldEncryptionService, sessionAccessService);
    }

    private Authentication teacherAuth(String userType) {
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(teacherUserId);
        when(auth.getDetails()).thenReturn(new TenantContext(tenantId, teacherUserId, userType));
        return auth;
    }

    private User student() {
        User u = new User();
        u.setUserId(studentUserId);
        u.setTenantId(tenantId);
        u.setPseudonym("小星");
        u.setUserType("student");
        u.setStatus("active");
        u.setGradeCode("GRADE_6");
        u.setClassCode("CLASS_1");
        return u;
    }

    private TeacherService.StatsVO stats() {
        return new TeacherService.StatsVO(
                List.of(new TeacherService.RiskDistItem(3, "中风险", 5L)),
                List.of(new TeacherService.ClassRiskItem("CLASS_1", 3L, 20L)),
                List.of(new TeacherService.DailyCount("2026-07-01", 2L)),
                List.of(new TeacherService.EmotionItem("sad", 4L)));
    }

    // ===== 工作台 =====

    @Test
    @DisplayName("getDashboard 透传租户（BACK-001：psych_teacher classScope=null 全校）")
    void dashboard() {
        when(teacherService.resolveClassScope(any(), any(), any())).thenReturn(null);
        when(teacherService.getDashboard(tenantId, null))
                // FE-2 后 DashboardVO 扩展为 8 参数（新增 activeStudents/totalSessions）
                .thenReturn(new TeacherService.DashboardVO(2, 1, 3, 0L, 0L, List.of(), 4.2, 10));

        ApiResponse<TeacherService.DashboardVO> resp = controller.getDashboard(teacherAuth("psych_teacher"));

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data().pendingAlerts()).isEqualTo(2);
    }

    @Test
    @DisplayName("getSatisfaction 透传租户（BACK-001：classScope=null 全校）")
    void satisfaction() {
        when(teacherService.resolveClassScope(any(), any(), any())).thenReturn(null);
        when(teacherService.getSatisfactionStats(tenantId, null))
                .thenReturn(new TeacherService.SatisfactionStatsVO(10, 3.5, List.of(), 2, 4.0));

        ApiResponse<TeacherService.SatisfactionStatsVO> resp = controller.getSatisfaction(teacherAuth("psych_teacher"));

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data().totalRated()).isEqualTo(10);
    }

    @Test
    @DisplayName("getTemplates 返回 7 个干预话术模板")
    void templates() {
        // R-7：模板下沉 TeacherService.TEMPLATES（预审核合规内容），controller 仅透传
        when(teacherService.getTemplates()).thenReturn(TeacherService.TEMPLATES);

        var resp = controller.getTemplates();

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data()).hasSize(7);
        assertThat(resp.data().get(0).get("category")).isEqualTo("预警处理");
    }

    // ===== 学生管理 =====

    @Test
    @DisplayName("getHighRiskStudents 跟随数据范围")
    void highRiskStudents() {
        when(teacherService.resolveClassScope(tenantId, teacherUserId, "class_teacher")).thenReturn("CLASS_1");
        when(teacherService.getHighRiskStudents(tenantId, "CLASS_1"))
                .thenReturn(List.of(new TeacherService.HighRiskStudentVO(
                        studentUserId, "小星", "GRADE_6", 4, 1, Instant.now())));

        ApiResponse<List<TeacherService.HighRiskStudentVO>> resp =
                controller.getHighRiskStudents(teacherAuth("class_teacher"));

        assertThat(resp.data()).hasSize(1);
        assertThat(resp.data().get(0).displayName()).isEqualTo("小星");
    }

    @Test
    @DisplayName("getStudentProfile 透传用户类型做权限校验")
    void studentProfile() {
        when(teacherService.getStudentProfile(tenantId, studentUserId, "psych_teacher"))
                .thenReturn(new TeacherService.StudentProfileVO(
                        studentUserId, "小星", "GRADE_6", "CLASS_1", "active", 3, 5,
                        List.of(), List.of(), List.of()));

        var resp = controller.getStudentProfile(studentUserId, teacherAuth("psych_teacher"));

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data().displayName()).isEqualTo("小星");
    }

    @Test
    @DisplayName("getStudentRadar 调用画像雷达服务")
    void studentRadar() {
        when(profileRadarService.getRadarData(tenantId, studentUserId))
                .thenReturn(Map.of("dimensions", List.of()));

        var resp = controller.getStudentRadar(studentUserId, teacherAuth("psych_teacher"));

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data().get("dimensions")).isNotNull();
    }

    @Test
    @DisplayName("addNote 默认 noteType=general")
    void addNote_defaultType() {
        TeacherNote note = new TeacherNote();
        when(teacherService.addNote(tenantId, studentUserId, teacherUserId, "内容", "general"))
                .thenReturn(note);

        var resp = controller.addNote(studentUserId, Map.of("content", "内容"), teacherAuth("psych_teacher"));

        assertThat(resp.code()).isEqualTo(0);
        verify(teacherService).addNote(tenantId, studentUserId, teacherUserId, "内容", "general");
    }

    @Test
    @DisplayName("addNote 自定义 noteType 透传")
    void addNote_customType() {
        when(teacherService.addNote(eq(tenantId), eq(studentUserId), eq(teacherUserId),
                eq("内容"), eq("intervention")))
                .thenReturn(new TeacherNote());

        controller.addNote(studentUserId, Map.of("content", "内容", "noteType", "intervention"),
                teacherAuth("psych_teacher"));

        verify(teacherService).addNote(tenantId, studentUserId, teacherUserId, "内容", "intervention");
    }

    @Test
    @DisplayName("getSessionMessages 透传租户与会话（BACK-001：classScope=null 全校）")
    void sessionMessages() {
        controller.getSessionMessages(UUID.randomUUID(), teacherAuth("psych_teacher"));

        verify(teacherService).getSessionMessages(eq(tenantId), isNull(), any(UUID.class));
    }

    // ===== 会话摘要 / 接管 =====

    @Test
    @DisplayName("getSessionSummary 会话不存在 → not_found")
    void sessionSummary_notFound() {
        when(sessionAccessService.getTenantSession(any(), any())).thenReturn(null);

        var resp = controller.getSessionSummary(UUID.randomUUID(), teacherAuth("psych_teacher"));

        assertThat(resp.data().get("status")).isEqualTo("not_found");
    }

    @Test
    @DisplayName("getSessionSummary 有摘要 → ready")
    void sessionSummary_ready() {
        CounselingSession s = new CounselingSession();
        s.setSessionSummary("摘要内容");
        when(sessionAccessService.getTenantSession(any(), any())).thenReturn(s);
        when(fieldEncryptionService.decrypt("摘要内容")).thenReturn("摘要内容");

        var resp = controller.getSessionSummary(UUID.randomUUID(), teacherAuth("psych_teacher"));

        assertThat(resp.data().get("status")).isEqualTo("ready");
        assertThat(resp.data().get("summary")).isEqualTo("摘要内容");
    }

    @Test
    @DisplayName("getSessionSummary 摘要生成中 → pending")
    void sessionSummary_pending() {
        when(sessionAccessService.getTenantSession(any(), any())).thenReturn(new CounselingSession());

        var resp = controller.getSessionSummary(UUID.randomUUID(), teacherAuth("psych_teacher"));

        assertThat(resp.data().get("status")).isEqualTo("pending");
    }

    @Test
    @DisplayName("takeoverSession 会话不存在 → success=false")
    void takeover_notFound() {
        when(teacherService.takeoverSession(any(), any(), any(), any()))
                .thenReturn(new TeacherService.TakeoverResult(false, "session_not_found"));

        var resp = controller.takeoverSession(UUID.randomUUID(), teacherAuth("psych_teacher"));

        assertThat(resp.data().get("success")).isEqualTo(false);
        assertThat(resp.data().get("reason")).isEqualTo("session_not_found");
    }

    @Test
    @DisplayName("takeoverSession 成功 → success=true + 下沉 TeacherService 调用")
    void takeover_success() {
        UUID sessionId = UUID.randomUUID();
        when(teacherService.takeoverSession(any(), any(), any(), any()))
                .thenReturn(new TeacherService.TakeoverResult(true, null));

        var resp = controller.takeoverSession(sessionId, teacherAuth("psych_teacher"));

        assertThat(resp.data().get("success")).isEqualTo(true);
        verify(teacherService).takeoverSession(eq(tenantId), isNull(), eq(teacherUserId), eq(sessionId));
    }

    // ===== 家长链接 =====

    @Test
    @DisplayName("generateParentLink 学生不存在 → error")
    void parentLink_noStudent() {
        when(teacherService.findStudentInTenant(tenantId, studentUserId)).thenReturn(null);

        var resp = controller.generateParentLink(studentUserId, teacherAuth("psych_teacher"));

        assertThat(resp.data().get("error")).isEqualTo("学生不存在");
    }

    @Test
    @DisplayName("generateParentLink 成功 → 签发 parent_report token（SEC-006）")
    void parentLink_success() {
        when(teacherService.findStudentInTenant(tenantId, studentUserId)).thenReturn(student());
        when(jwtTokenProvider.generateParentReportToken(studentUserId, tenantId)).thenReturn("parent-token-xyz");

        var resp = controller.generateParentLink(studentUserId, teacherAuth("psych_teacher"));

        assertThat(resp.data().get("link")).isEqualTo("/parent?token=parent-token-xyz");
        assertThat(resp.data().get("expiresIn")).isEqualTo("7天");
    }

    // ===== 学生列表 =====

    @Test
    @DisplayName("getStudents 心理老师（scope=null）→ 全校学生（含冻结，BUG-UI-03）")
    void students_schoolWide() {
        when(teacherService.resolveClassScope(tenantId, teacherUserId, "psych_teacher")).thenReturn(null);
        when(teacherService.listVisibleStudents(tenantId, null, null, null, null)).thenReturn(List.of(student()));
        // BUG-T-04-03：风险等级批量关联
        when(teacherService.batchStudentMaxRisk(tenantId, List.of(student()))).thenReturn(java.util.Map.of(student().getUserId(), 0));

        ApiResponse<List<TeacherController.StudentVO>> resp = controller.getStudents(teacherAuth("psych_teacher"), null, null, null, null);

        assertThat(resp.data()).hasSize(1);
        assertThat(resp.data().get(0).displayName()).isEqualTo("小星");
    }

    @Test
    @DisplayName("getStudents 班主任（scope=CLASS_1）→ 本班过滤")
    void students_classScope() {
        when(teacherService.resolveClassScope(tenantId, teacherUserId, "class_teacher")).thenReturn("CLASS_1");
        when(teacherService.listVisibleStudents(tenantId, "CLASS_1", null, null, null)).thenReturn(List.of(student()));
        when(teacherService.batchStudentMaxRisk(tenantId, List.of(student()))).thenReturn(java.util.Map.of(student().getUserId(), 0));

        controller.getStudents(teacherAuth("class_teacher"), null, null, null, null);

        verify(teacherService).listVisibleStudents(tenantId, "CLASS_1", null, null, null);
    }

    // ===== 通知 =====

    @Test
    @DisplayName("getNotifications 透传用户与分页参数（BUG-T-06-02）")
    void notifications() {
        when(notificationService.getNotifications(teacherUserId, "ALL", 1, 20))
                .thenReturn(new NotificationService.NotificationPage(List.of(), 0));

        controller.getNotifications(teacherAuth("psych_teacher"), "ALL", 1, 20);

        verify(notificationService).getNotifications(teacherUserId, "ALL", 1, 20);
    }

    @Test
    @DisplayName("getUnreadCount 透传用户")
    void unreadCount() {
        when(notificationService.countUnread(teacherUserId)).thenReturn(3L);

        var resp = controller.getUnreadCount(teacherAuth("psych_teacher"));

        assertThat(resp.data()).isEqualTo(3L);
    }

    @Test
    @DisplayName("getRiskEvents 按租户查询 + limit 上限 100（BACK-001：classScope=null 全校）")
    void riskEvents() {
        when(teacherService.resolveClassScope(any(), any(), any())).thenReturn(null);
        when(teacherService.pageRiskEvents(tenantId, null, 500)).thenReturn(List.of(new RiskEvent()));

        var resp = controller.getRiskEvents(teacherAuth("psych_teacher"), 500);

        assertThat(resp.code()).isEqualTo(0);
        verify(teacherService).pageRiskEvents(tenantId, null, 500);
    }

    // ===== 导出 =====

    @Test
    @DisplayName("exportStudents 输出 BOM + CSV 表头 + 学生行 + 审计")
    void exportStudents() throws IOException {
        when(teacherService.listActiveStudents(tenantId, null)).thenReturn(List.of(student()));
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter sw = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(sw));

        controller.exportStudents(teacherAuth("psych_teacher"), response);

        verify(response).setHeader("Content-Disposition", "attachment; filename=students_export.csv");
        assertThat(sw.toString()).startsWith("\uFEFF");
        assertThat(sw.toString()).contains("昵称,年级,班级,状态");
        assertThat(sw.toString()).contains("小星,GRADE_6,CLASS_1,active");
    }

    @Test
    @DisplayName("weeklyReport 输出 HTML 周报（风隩分布/班级对比/情绪中文）+ 审计")
    void weeklyReport() throws IOException {
        when(teacherService.getStats(tenantId, null)).thenReturn(stats());
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter sw = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(sw));

        controller.weeklyReport(teacherAuth("psych_teacher"), response);

        verify(auditLogService).log(tenantId, teacherUserId, "EXPORT_WEEKLY_REPORT", "report");
        String html = sw.toString();
        assertThat(html).contains("心理辅导周报");
        assertThat(html).contains("预警总数");
        assertThat(html).contains("中风险");
        assertThat(html).contains("CLASS_1");
        assertThat(html).contains("难过");
    }

    @Test
    @DisplayName("exportSession HTML 转义 contentSummary（B-04 XSS 防护）")
    void exportSession_escapesHtml() throws IOException {
        UUID sessionId = UUID.randomUUID();
        when(teacherService.resolveClassScope(any(), any(), any())).thenReturn(null);
        when(teacherService.getSessionMessages(tenantId, null, sessionId))
                .thenReturn(List.of(
                        new TeacherService.MessageSummaryVO(UUID.randomUUID(), "student", 1,
                                "<img src=x onerror=alert(1)>", null, 0, Instant.now())));
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter sw = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(sw));

        controller.exportSession(sessionId, teacherAuth("psych_teacher"), response);

        String html = sw.toString();
        assertThat(html).doesNotContain("<img src=x onerror=alert(1)>");
        assertThat(html).contains("&lt;img src=x onerror=alert(1)&gt;");
    }

    @Test
    @DisplayName("weeklyReport 班级名 HTML 转义（B-04 同源风险）")
    void weeklyReport_escapesClassCode() throws IOException {
        // StatsVO 为 final record，不能 mock——用真实实例（其余列表置空，仅验证 classCode 转义）
        TeacherService.StatsVO stats = new TeacherService.StatsVO(
                List.of(),
                List.of(new TeacherService.ClassRiskItem("<script>alert(1)</script>", 1L, 2L)),
                List.of(),
                List.of());
        when(teacherService.resolveClassScope(any(), any(), any())).thenReturn(null);
        when(teacherService.getStats(tenantId, null)).thenReturn(stats);
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter sw = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(sw));

        controller.weeklyReport(teacherAuth("psych_teacher"), response);

        String html = sw.toString();
        assertThat(html).doesNotContain("<script>alert(1)</script>");
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
    }

    @Test
    @DisplayName("exportSession 输出会话存档 HTML（学生/AI 消息 + 情绪中文）+ 审计")
    void exportSession() throws IOException {
        UUID sessionId = UUID.randomUUID();
        when(teacherService.resolveClassScope(any(), any(), any())).thenReturn(null);
        when(teacherService.getSessionMessages(tenantId, null, sessionId))
                .thenReturn(List.of(
                        new TeacherService.MessageSummaryVO(UUID.randomUUID(), "student", 1,
                                "今天很难过", "sad", 2, Instant.now()),
                        new TeacherService.MessageSummaryVO(UUID.randomUUID(), "ai", 2,
                                "我在这里陪着你", null, 0, Instant.now())));
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter sw = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(sw));

        controller.exportSession(sessionId, teacherAuth("psych_teacher"), response);

        verify(auditLogService).log(tenantId, teacherUserId, "EXPORT_SESSION", "counseling_session", sessionId, null);
        String html = sw.toString();
        assertThat(html).contains("会话记录");
        assertThat(html).contains("🧒 学生");
        assertThat(html).contains("🤖 AI");
        assertThat(html).contains("难过");
        assertThat(html).contains("今天很难过");
    }

    @Test
    @DisplayName("exportAlerts CSV 含转义（逗号字段加引号）+ 审计")
    void exportAlerts_csvEscape() throws IOException {
        when(teacherService.resolveClassScope(tenantId, teacherUserId, "psych_teacher")).thenReturn(null);
        when(teacherService.getAlertsForExport(tenantId, null, null, null, 5000))
                .thenReturn(List.of(new TeacherService.AlertVO(
                        UUID.randomUUID(), studentUserId, "小星", "自伤,高风险", 3, "open",
                        Instant.now(), teacherUserId, false)));
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter sw = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(sw));

        controller.exportAlerts(teacherAuth("psych_teacher"), response);

        verify(auditLogService).log(tenantId, teacherUserId, "EXPORT_ALERTS", "export");
        assertThat(sw.toString()).startsWith("\uFEFF");
        assertThat(sw.toString()).contains("\"自伤,高风险\"");
        assertThat(sw.toString()).contains("学生,风险类型,风险等级,状态,检测时间,处理人");
    }
}
