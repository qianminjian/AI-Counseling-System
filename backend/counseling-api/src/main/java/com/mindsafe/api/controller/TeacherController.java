package com.mindsafe.api.controller;

import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.api.security.JwtTokenProvider;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.Notification;
import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.entity.TeacherNote;
import com.mindsafe.domain.entity.User;
import com.mindsafe.service.notification.NotificationService;
import com.mindsafe.service.casemanage.CaseLifecycleService;
import com.mindsafe.service.profile.ProfileRadarService;
import com.mindsafe.service.teacher.TeacherService;
import com.mindsafe.service.audit.AuditLogService;
import com.mindsafe.service.security.FieldEncryptionService;
import com.mindsafe.service.session.SessionAccessService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 教师端 API（对齐 design/16 §4）
 * <p>
 * 功能：工作台概览 / 预警队列 / 认领&误报 / 学生档案 / 备注 / 通知
 */
@RestController
@RequestMapping("/api/v1")
public class TeacherController {

    private final NotificationService notificationService;
    private final TeacherService teacherService;
    private final ProfileRadarService profileRadarService;
    private final AuditLogService auditLogService;
    private final JwtTokenProvider jwtTokenProvider;
    private final FieldEncryptionService fieldEncryptionService;
    /** T4 批次A：会话归属校验单点（租户条件强制内置） */
    private final SessionAccessService sessionAccessService;

    public TeacherController(NotificationService notificationService,
                             TeacherService teacherService,
                             ProfileRadarService profileRadarService,
                             AuditLogService auditLogService,
                             JwtTokenProvider jwtTokenProvider,
                             FieldEncryptionService fieldEncryptionService,
                             SessionAccessService sessionAccessService) {
        this.notificationService = notificationService;
        this.teacherService = teacherService;
        this.profileRadarService = profileRadarService;
        this.auditLogService = auditLogService;
        this.jwtTokenProvider = jwtTokenProvider;
        this.fieldEncryptionService = fieldEncryptionService;
        this.sessionAccessService = sessionAccessService;
    }

    // ===== 工作台 =====

    /** 工作台概览（待处理预警数/今日任务/周趋势） */
    @GetMapping("/teacher/dashboard")
    public ApiResponse<TeacherService.DashboardVO> getDashboard(Authentication auth) {
        TenantContext ctx = (TenantContext) auth.getDetails();
        UUID userId = (UUID) auth.getPrincipal();
        return ApiResponse.ok(teacherService.getDashboard(ctx.tenantId(), userId));
    }

    /** 数据看板统计（风隩分布/班级对比/会话趋势/情绪分布） */
    @GetMapping("/teacher/stats")
    public ApiResponse<TeacherService.StatsVO> getStats(Authentication auth) {
        TenantContext ctx = (TenantContext) auth.getDetails();
        String classScope = teacherService.resolveClassScope(ctx.tenantId(), ctx.userId(), ctx.userType());
        return ApiResponse.ok(teacherService.getStats(ctx.tenantId(), classScope));
    }
    
    /** 满意度统计（平均评分/分布/近 7 天趋势） */
    @GetMapping("/teacher/satisfaction")
    public ApiResponse<TeacherService.SatisfactionStatsVO> getSatisfaction(Authentication auth) {
        TenantContext ctx = (TenantContext) auth.getDetails();
        return ApiResponse.ok(teacherService.getSatisfactionStats(ctx.tenantId()));
    }

    // ===== 干预话术模板 =====

    private static final java.util.List<java.util.Map<String, String>> TEMPLATES = java.util.List.of(
        java.util.Map.of("id", "t1", "category", "预警处理", "content", "已与学生进行一对一谈话，学生情绪稳定，表示只是随口说说。已告知班主任关注。"),
        java.util.Map.of("id", "t2", "category", "预警处理", "content", "已联系家长沟通，家长表示近期家庭有变动，会配合关注学生情绪变化。"),
        java.util.Map.of("id", "t3", "category", "预警处理", "content", "误报。学生是在讨论课文内容/新闻事件，非自身情绪表达。"),
        java.util.Map.of("id", "t4", "category", "个案备注", "content", "学生近期情绪低落，已安排每周一次心理辅导，持续跟踪。"),
        java.util.Map.of("id", "t5", "category", "个案备注", "content", "学生状态明显好转，主动参与课堂活动，建议降低关注等级。"),
        java.util.Map.of("id", "t6", "category", "家长沟通", "content", "建议家长多关注孩子情绪变化，保持开放沟通，避免过度施压。如持续异常请联系学校心理老师。"),
        java.util.Map.of("id", "t7", "category", "转介建议", "content", "学生情况超出学校辅导能力，建议转介至专业心理机构进一步评估。")
    );

    /** 获取干预话术模板列表 */
    @GetMapping("/teacher/templates")
    public ApiResponse<java.util.List<java.util.Map<String, String>>> getTemplates() {
        return ApiResponse.ok(TEMPLATES);
    }

    // ===== 学生管理 =====

    /** 高风险学生列表 */
    @GetMapping("/teacher/students/high-risk")
    public ApiResponse<List<TeacherService.HighRiskStudentVO>> getHighRiskStudents(Authentication auth) {
        TenantContext ctx = (TenantContext) auth.getDetails();
        String classScope = teacherService.resolveClassScope(ctx.tenantId(), ctx.userId(), ctx.userType());
        return ApiResponse.ok(teacherService.getHighRiskStudents(ctx.tenantId(), classScope));
    }

    /** 学生档案（对话摘要/预警历史/备注） */
    @GetMapping("/teacher/students/{id}")
    public ApiResponse<TeacherService.StudentProfileVO> getStudentProfile(
            @PathVariable UUID id, Authentication auth) {
        TenantContext ctx = (TenantContext) auth.getDetails();
        return ApiResponse.ok(teacherService.getStudentProfile(ctx.tenantId(), id, ctx.userType()));
    }

    /** 学生画像雷达图（PROF-004，6 维度 + 里程碑） */
    @GetMapping("/teacher/students/{id}/radar")
    public ApiResponse<Map<String, Object>> getStudentRadar(
            @PathVariable UUID id, Authentication auth) {
        TenantContext ctx = (TenantContext) auth.getDetails();
        return ApiResponse.ok(profileRadarService.getRadarData(ctx.tenantId(), id));
    }

    /** 添加备注 */
    @PostMapping("/teacher/students/{id}/notes")
    public ApiResponse<TeacherNote> addNote(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body,
            Authentication auth) {
        TenantContext ctx = (TenantContext) auth.getDetails();
        UUID userId = (UUID) auth.getPrincipal();
        String content = body.get("content");
        String noteType = body.getOrDefault("noteType", "general");
        TeacherNote note = teacherService.addNote(ctx.tenantId(), id, userId, content, noteType);
        return ApiResponse.ok(note);
    }

    /** 查看某次会话的对话摘要（教师端） */
    @GetMapping("/teacher/sessions/{sessionId}/messages")
    public ApiResponse<List<TeacherService.MessageSummaryVO>> getSessionMessages(
            @PathVariable UUID sessionId, Authentication auth) {
        TenantContext ctx = (TenantContext) auth.getDetails();
        return ApiResponse.ok(teacherService.getSessionMessages(ctx.tenantId(), sessionId));
    }

    /** 获取会话 AI 摘要（教师端） */
    @GetMapping("/teacher/sessions/{sessionId}/summary")
    public ApiResponse<Map<String, Object>> getSessionSummary(
            @PathVariable UUID sessionId, Authentication auth) {
        TenantContext ctx = (TenantContext) auth.getDetails();
        // T4 批次A：归属校验单点（租户条件强制内置，杜绝跨租户越权）
        CounselingSession session = sessionAccessService.getTenantSession(ctx.tenantId(), sessionId);
        if (session == null) {
            return ApiResponse.ok(Map.of("summary", "", "status", "not_found"));
        }
        // AUDIT-P1-8：session_summary 密文存储，教师端读取时解密（明文兼容透传）
        String summary = fieldEncryptionService.decrypt(session.getSessionSummary());
        String status = summary != null ? "ready" : "pending";
        return ApiResponse.ok(Map.of("summary", summary != null ? summary : "", "status", status));
    }

    /** 教师接管升级会话（红色风隩转人工） */
    @PostMapping("/teacher/sessions/{sessionId}/takeover")
    public ApiResponse<Map<String, Object>> takeoverSession(
            @PathVariable UUID sessionId, Authentication auth) {
        TenantContext ctx = (TenantContext) auth.getDetails();
        UUID userId = (UUID) auth.getPrincipal();
        // T4 批次A/B：归属校验 + 状态更新 + 审计整体下沉 TeacherService（事务内）
        TeacherService.TakeoverResult result = teacherService.takeoverSession(ctx.tenantId(), userId, sessionId);
        if (!result.success()) {
            return ApiResponse.ok(Map.of("success", false, "reason", result.reason()));
        }
        return ApiResponse.ok(Map.of("success", true, "sessionId", sessionId.toString()));
    }

    /** 生成家长周报链接（7 天有效） */
    @PostMapping("/teacher/students/{studentId}/parent-link")
    public ApiResponse<Map<String, String>> generateParentLink(
            @PathVariable UUID studentId, Authentication auth) {
        TenantContext ctx = (TenantContext) auth.getDetails();
        // 验证学生属于同租户（T4 批次C：查询下沉 TeacherService，租户条件强制内置）
        User student = teacherService.findStudentInTenant(ctx.tenantId(), studentId);
        if (student == null) {
            return ApiResponse.ok(Map.of("error", "学生不存在"));
        }
        // SEC-006：生成 parent_report 类型 JWT（userId=studentId，userType=parent，7 天 TTL，与 expiresIn 声明一致）
        String parentToken = jwtTokenProvider.generateParentReportToken(studentId, ctx.tenantId());
        String link = "/parent?token=" + parentToken;
        return ApiResponse.ok(Map.of("link", link, "expiresIn", "7天"));
    }

    /** 获取学生列表（同租户，班主任仅看本班；T4 批次C：查询下沉 TeacherService） */
    @GetMapping("/teacher/students")
    public ApiResponse<List<StudentVO>> getStudents(Authentication auth) {
        TenantContext ctx = (TenantContext) auth.getDetails();
        String classScope = teacherService.resolveClassScope(ctx.tenantId(), ctx.userId(), ctx.userType());
        List<User> students = teacherService.listActiveStudents(ctx.tenantId(), classScope);
        List<StudentVO> voList = students.stream()
                .map(s -> new StudentVO(s.getUserId(), s.getPseudonym(), s.getGradeCode(), s.getClassCode()))
                .toList();
        return ApiResponse.ok(voList);
    }

    // ===== 通知（保留 M1 接口） =====

    /** 获取当前教师的通知列表 */
    @GetMapping("/teacher/notifications")
    public ApiResponse<List<Notification>> getNotifications(
            Authentication auth,
            @RequestParam(defaultValue = "50") int limit) {
        UUID userId = (UUID) auth.getPrincipal();
        return ApiResponse.ok(notificationService.getNotifications(userId, limit));
    }

    /** 获取未读通知数量 */
    @GetMapping("/teacher/notifications/unread-count")
    public ApiResponse<Long> getUnreadCount(Authentication auth) {
        UUID userId = (UUID) auth.getPrincipal();
        return ApiResponse.ok(notificationService.countUnread(userId));
    }

    /** 标记通知为已读（P1 审计修复：携带收件人 ID，防 IDOR） */
    @PutMapping("/teacher/notifications/{id}/read")
    public ApiResponse<Void> markAsRead(@PathVariable UUID id, Authentication auth) {
        UUID userId = (UUID) auth.getPrincipal();
        notificationService.markAsRead(id, userId);
        return ApiResponse.ok(null);
    }

    /** 获取风险事件列表（同租户；T4 批次C：分页查询下沉 TeacherService） */
    @GetMapping("/teacher/risk-events")
    public ApiResponse<List<RiskEvent>> getRiskEvents(
            Authentication auth,
            @RequestParam(defaultValue = "50") int limit) {
        TenantContext ctx = (TenantContext) auth.getDetails();
        return ApiResponse.ok(teacherService.pageRiskEvents(ctx.tenantId(), limit));
    }

    // ===== 数据导出 =====

    private static final DateTimeFormatter CSV_DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.of("Asia/Shanghai"));

    /** 导出预警记录 CSV */
    @GetMapping("/teacher/export/alerts")
    public void exportAlerts(Authentication auth, HttpServletResponse response) throws IOException {
        TenantContext ctx = (TenantContext) auth.getDetails();
        UUID userId = (UUID) auth.getPrincipal();
        auditLogService.log(ctx.tenantId(), userId, "EXPORT_ALERTS", "export");
        // P1 审计修复：导出跟随数据范围（班主任仅导出本班，不再全校可见）
        String classScope = teacherService.resolveClassScope(ctx.tenantId(), ctx.userId(), ctx.userType());
        List<TeacherService.AlertVO> alerts = teacherService.getAlerts(ctx.tenantId(), classScope, null, null, 500);

        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=alerts_export.csv");
        // BOM for Excel 中文兼容
        response.getOutputStream().write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});

        PrintWriter w = response.getWriter();
        w.println("学生,风险类型,风险等级,状态,检测时间,处理人");
        for (var a : alerts) {
            w.printf("%s,%s,%d,%s,%s,%s%n",
                    csv(a.studentName()), csv(a.riskType()), a.riskLevel(),
                    csv(a.status()),
                    a.detectedAt() != null ? CSV_DATE_FMT.format(a.detectedAt()) : "",
                    a.assignedUserId() != null ? a.assignedUserId().toString().substring(0, 8) : "");
        }
        w.flush();
    }

    /** 导出学生列表 CSV（T4 批次C：查询下沉 TeacherService，与 getStudents 共用 DRY） */
    @GetMapping("/teacher/export/students")
    public void exportStudents(Authentication auth, HttpServletResponse response) throws IOException {
        TenantContext ctx = (TenantContext) auth.getDetails();
        List<User> students = teacherService.listActiveStudents(ctx.tenantId(), null);

        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=students_export.csv");
        response.getOutputStream().write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});

        PrintWriter w = response.getWriter();
        w.println("昵称,年级,班级,状态");
        for (var s : students) {
            w.printf("%s,%s,%s,%s%n",
                    csv(s.getPseudonym()), csv(s.getGradeCode()), csv(s.getClassCode()), csv(s.getStatus()));
        }
        w.flush();
    }

    /** CSV 字段转义（含逗号/引号/换行的值加双引号包裹） */
    private static String csv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /** 学生视图对象 */
    public record StudentVO(UUID userId, String displayName, String gradeCode, String classCode) {}

    // ===== 个案管理（WB-003，design/35 M3） =====

    /** 个案阶段推进（建案→评估→干预跟踪→结案，P1 审计修复：读取真实当前阶段并持久化） */
    @PostMapping("/teacher/cases/{studentId}/transition")
    public ApiResponse<Map<String, Object>> transitionCase(
            @PathVariable UUID studentId,
            @RequestBody Map<String, String> body,
            Authentication auth) {
        TenantContext ctx = (TenantContext) auth.getDetails();
        UUID userId = (UUID) auth.getPrincipal();
        String targetStage = body.getOrDefault("targetStage", "ASSESSMENT");

        // P1 审计修复：非法阶段值 → 400 参数错误（不再 500）
        CaseLifecycleService.CaseStage target;
        try {
            target = CaseLifecycleService.CaseStage.valueOf(targetStage);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "非法个案阶段: " + targetStage);
        }

        CaseLifecycleService.StageTransition result =
                teacherService.transitionCaseStage(ctx.tenantId(), studentId, userId, target);

        auditLogService.log(ctx.tenantId(), userId, "CASE_TRANSITION", User.USER_TYPE_STUDENT, studentId, targetStage);
        return ApiResponse.ok(Map.of(
                "allowed", result.allowed(),
                "newStage", result.to().name(),
                "reason", result.reason() != null ? result.reason() : ""));
    }

    // ===== 周报导出（可打印 HTML） =====

    /** 生成周报告 HTML（教师浏览器 Ctrl+P 保存 PDF） */
    @GetMapping(value = "/teacher/report/weekly", produces = "text/html; charset=UTF-8")
    public void weeklyReport(Authentication auth, HttpServletResponse response) throws IOException {
        TenantContext ctx = (TenantContext) auth.getDetails();
        UUID userId = (UUID) auth.getPrincipal();
        auditLogService.log(ctx.tenantId(), userId, "EXPORT_WEEKLY_REPORT", "report");

        var stats = teacherService.getStats(ctx.tenantId(), null);
        String now = java.time.LocalDate.now().toString();

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset='UTF-8'>");
        html.append("<title>心理辅导周报 - ").append(now).append("</title>");
        html.append("<style>body{font-family:'PingFang SC',sans-serif;padding:40px;color:#333}");
        html.append("h1{font-size:22px;border-bottom:2px solid #1890ff;padding-bottom:8px}");
        html.append("table{width:100%;border-collapse:collapse;margin:16px 0}");
        html.append("th,td{border:1px solid #ddd;padding:8px 12px;text-align:left;font-size:13px}");
        html.append("th{background:#f5f7fa}.stat{display:inline-block;margin:0 24px 12px 0}");
        html.append(".stat b{font-size:28px;color:#1890ff;display:block}.stat span{font-size:12px;color:#999}");
        html.append("@media print{body{padding:20px}}</style></head><body>");
        html.append("<h1>🧠 AI 心理辅导系统 — 周报</h1>");
        html.append("<p style='color:#999;font-size:12px'>报告日期：").append(now).append("</p>");

        // 概览统计
        html.append("<div>");
        long totalAlerts = stats.riskDistribution().stream().mapToLong(TeacherService.RiskDistItem::count).sum();
        html.append("<div class='stat'><b>").append(totalAlerts).append("</b><span>预警总数</span></div>");
        html.append("<div class='stat'><b>").append(stats.sessionTrend().size()).append("</b><span>活跃天数</span></div>");
        long totalSessions = stats.sessionTrend().stream().mapToLong(TeacherService.DailyCount::count).sum();
        html.append("<div class='stat'><b>").append(totalSessions).append("</b><span>会话总数</span></div>");
        html.append("</div>");

        // 风隩分布表
        html.append("<h3>风隩分布</h3><table><tr><th>等级</th><th>数量</th></tr>");
        for (var item : stats.riskDistribution()) {
            html.append("<tr><td>").append(item.label()).append("</td><td>").append(item.count()).append("</td></tr>");
        }
        html.append("</table>");

        // 班级对比表
        html.append("<h3>班级对比</h3><table><tr><th>班级</th><th>预警数</th><th>学生数</th></tr>");
        for (var item : stats.classComparison()) {
            html.append("<tr><td>").append(item.classCode()).append("</td><td>")
                .append(item.alertCount()).append("</td><td>").append(item.studentCount()).append("</td></tr>");
        }
        html.append("</table>");

        // 情绪分布
        html.append("<h3>情绪分布</h3><table><tr><th>情绪</th><th>次数</th></tr>");
        for (var item : stats.emotionDistribution()) {
            html.append("<tr><td>").append(emotionZh(item.emotion())).append("</td><td>").append(item.count()).append("</td></tr>");
        }
        html.append("</table>");

        html.append("<p style='margin-top:32px;color:#bbb;font-size:11px'>—— MindSafe AI 心理辅导系统自动生成 ——</p>");
        html.append("</body></html>");

        response.setContentType("text/html; charset=UTF-8");
        response.getWriter().write(html.toString());
        response.getWriter().flush();
    }

    /** 单会话导出（个案存档，可打印 HTML） */
    @GetMapping(value = "/teacher/sessions/{sessionId}/export", produces = "text/html; charset=UTF-8")
    public void exportSession(@PathVariable UUID sessionId, Authentication auth, HttpServletResponse response) throws IOException {
        TenantContext ctx = (TenantContext) auth.getDetails();
        auditLogService.log(ctx.tenantId(), (UUID) auth.getPrincipal(), "EXPORT_SESSION", "counseling_session", sessionId, null);

        var messages = teacherService.getSessionMessages(ctx.tenantId(), sessionId);

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset='UTF-8'>");
        html.append("<title>会话记录 - ").append(sessionId).append("</title>");
        html.append("<style>body{font-family:'PingFang SC',sans-serif;padding:40px;color:#333;max-width:700px;margin:0 auto}");
        html.append("h1{font-size:18px;border-bottom:2px solid #1890ff;padding-bottom:8px}");
        html.append(".msg{margin:12px 0;padding:10px 14px;border-radius:8px;font-size:13px;line-height:1.6}");
        html.append(".student{background:#e6f7ff;margin-left:40px}.ai{background:#f6ffed;margin-right:40px}");
        html.append(".meta{font-size:11px;color:#999;margin-bottom:4px}");
        html.append("@media print{body{padding:20px}}</style></head><body>");
        html.append("<h1>🛡️ MindSafe 会话记录（个案存档）</h1>");
        html.append("<p style='color:#999;font-size:12px'>会话 ID：").append(sessionId).append(" | 导出时间：")
            .append(java.time.LocalDateTime.now().toString().substring(0, 16)).append("</p><hr>");

        for (var msg : messages) {
            boolean isStudent = User.USER_TYPE_STUDENT.equals(msg.senderType());
            html.append("<div class='msg ").append(isStudent ? "student" : "ai").append("'>");
            html.append("<div class='meta'>").append(isStudent ? "🧒 学生" : "🤖 AI");
            if (msg.emotionLabel() != null) html.append(" · ").append(emotionZh(msg.emotionLabel()));
            html.append("</div>");
            html.append("<div>").append(msg.contentSummary() != null ? msg.contentSummary() : "").append("</div>");
            html.append("</div>");
        }

        html.append("<p style='margin-top:32px;color:#bbb;font-size:11px'>—— MindSafe AI 心理辅导系统 · 机密文件 ——</p>");
        html.append("</body></html>");

        response.setContentType("text/html; charset=UTF-8");
        response.getWriter().write(html.toString());
        response.getWriter().flush();
    }

    /** 情绪码值 → 中文标签（DC-008：EmotionVocabulary.ZH_LABELS 单一标签源，anxious→紧张 全系统单译） */
    private static String emotionZh(String code) {
        return com.mindsafe.ai.risk.EmotionVocabulary.labelOf(code);
    }
}
