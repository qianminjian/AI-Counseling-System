package com.mindsafe.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.api.security.JwtTokenProvider;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.MessageSummary;
import com.mindsafe.domain.entity.Notification;
import com.mindsafe.domain.entity.QualityScore;
import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.entity.TeacherNote;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.domain.mapper.MessageSummaryMapper;
import com.mindsafe.domain.mapper.QualityScoreMapper;
import com.mindsafe.domain.mapper.RiskEventMapper;
import com.mindsafe.domain.mapper.UserMapper;
import com.mindsafe.service.notification.NotificationService;
import com.mindsafe.service.profile.ProfileRadarService;
import com.mindsafe.service.teacher.TeacherService;
import com.mindsafe.service.audit.AuditLogService;
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
    private final RiskEventMapper riskEventMapper;
    private final UserMapper userMapper;
    private final CounselingSessionMapper sessionMapper;
    private final QualityScoreMapper qualityScoreMapper;
    private final MessageSummaryMapper messageSummaryMapper;
    private final AuditLogService auditLogService;
    private final JwtTokenProvider jwtTokenProvider;

    public TeacherController(NotificationService notificationService,
                             TeacherService teacherService,
                             ProfileRadarService profileRadarService,
                             RiskEventMapper riskEventMapper,
                             UserMapper userMapper,
                             CounselingSessionMapper sessionMapper,
                             QualityScoreMapper qualityScoreMapper,
                             MessageSummaryMapper messageSummaryMapper,
                             AuditLogService auditLogService,
                             JwtTokenProvider jwtTokenProvider) {
        this.notificationService = notificationService;
        this.teacherService = teacherService;
        this.profileRadarService = profileRadarService;
        this.riskEventMapper = riskEventMapper;
        this.userMapper = userMapper;
        this.sessionMapper = sessionMapper;
        this.qualityScoreMapper = qualityScoreMapper;
        this.messageSummaryMapper = messageSummaryMapper;
        this.auditLogService = auditLogService;
        this.jwtTokenProvider = jwtTokenProvider;
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

    /** 质量监控：低分会话列表（rating <= 2） */
    @GetMapping("/teacher/quality/flagged")
    public ApiResponse<java.util.List<java.util.Map<String, Object>>> getFlaggedSessions(Authentication auth) {
        TenantContext ctx = (TenantContext) auth.getDetails();
        var flagged = sessionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.mindsafe.domain.entity.CounselingSession>()
                        .eq(com.mindsafe.domain.entity.CounselingSession::getTenantId, ctx.tenantId())
                        .isNotNull(com.mindsafe.domain.entity.CounselingSession::getSatisfactionRating)
                        .le(com.mindsafe.domain.entity.CounselingSession::getSatisfactionRating, 2)
                        .orderByDesc(com.mindsafe.domain.entity.CounselingSession::getStartedAt)
                        .last("LIMIT 50")
        );
        var result = flagged.stream().map(s -> {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("sessionId", s.getSessionId());
            m.put("studentUserId", s.getStudentUserId());
            m.put("rating", s.getSatisfactionRating());
            m.put("comment", s.getSatisfactionComment());
            m.put("startedAt", s.getStartedAt());
            m.put("sessionStatus", s.getSessionStatus());
            return m;
        }).toList();
        return ApiResponse.ok(result);
    }

    /** 质量监控：概览指标 */
    @GetMapping("/teacher/quality/stats")
    public ApiResponse<java.util.Map<String, Object>> getQualityStats(Authentication auth) {
        TenantContext ctx = (TenantContext) auth.getDetails();
        var stats = teacherService.getSatisfactionStats(ctx.tenantId());
        long flaggedCount = stats.distribution().stream()
                .filter(d -> d.stars() <= 2).mapToLong(d -> d.count()).sum();
        double flagRate = stats.totalRated() > 0 ? (double) flaggedCount / stats.totalRated() * 100 : 0;

        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("totalRated", stats.totalRated());
        result.put("avgRating", stats.avgRating());
        result.put("flaggedCount", flaggedCount);
        result.put("flagRate", Math.round(flagRate * 10) / 10.0);
        result.put("recentAvg", stats.recentAvg());
        return ApiResponse.ok(result);
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

    // ===== 预警队列 =====

    /** 预警队列（分页/筛选/排序） */
    @GetMapping("/alerts")
    public ApiResponse<List<TeacherService.AlertVO>> getAlerts(
            Authentication auth,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer minLevel,
            @RequestParam(defaultValue = "50") int limit) {
        TenantContext ctx = (TenantContext) auth.getDetails();
        return ApiResponse.ok(teacherService.getAlerts(ctx.tenantId(), status, minLevel, limit));
    }

    /** 认领预警 */
    @PostMapping("/alerts/{id}/claim")
    public ApiResponse<Void> claimAlert(@PathVariable UUID id, Authentication auth) {
        TenantContext ctx = (TenantContext) auth.getDetails();
        UUID userId = (UUID) auth.getPrincipal();
        teacherService.claimAlert(ctx.tenantId(), id, userId);
        auditLogService.log(ctx.tenantId(), userId, "ALERT_CLAIM", "risk_event", id, null);
        return ApiResponse.ok(null);
    }

    /** 标记误报 */
    @PatchMapping("/alerts/{id}/false-positive")
    public ApiResponse<Void> markFalsePositive(@PathVariable UUID id, Authentication auth) {
        TenantContext ctx = (TenantContext) auth.getDetails();
        UUID userId = (UUID) auth.getPrincipal();
        teacherService.markFalsePositive(ctx.tenantId(), id, userId);
        return ApiResponse.ok(null);
    }

    /** 处理完成（线下干预后标记 resolved，可附处理记录） */
    @PostMapping("/alerts/{id}/resolve")
    public ApiResponse<Void> resolveAlert(@PathVariable UUID id,
                                          @RequestBody(required = false) Map<String, String> body,
                                          Authentication auth) {
        TenantContext ctx = (TenantContext) auth.getDetails();
        UUID userId = (UUID) auth.getPrincipal();
        String note = body != null ? body.get("resolutionNote") : null;
        teacherService.resolveAlert(ctx.tenantId(), id, userId, note);
        auditLogService.log(ctx.tenantId(), userId, "ALERT_RESOLVE", "risk_event", id, note);
        return ApiResponse.ok(null);
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
        return ApiResponse.ok(teacherService.getStudentProfile(ctx.tenantId(), id));
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
        CounselingSession session = sessionMapper.selectOne(
                new LambdaQueryWrapper<CounselingSession>()
                        .eq(CounselingSession::getTenantId, ctx.tenantId())
                        .eq(CounselingSession::getSessionId, sessionId)
        );
        if (session == null) {
            return ApiResponse.ok(Map.of("summary", "", "status", "not_found"));
        }
        String summary = session.getSessionSummary();
        String status = summary != null ? "ready" : "pending";
        return ApiResponse.ok(Map.of("summary", summary != null ? summary : "", "status", status));
    }

    /** 教师接管升级会话（红色风隩转人工） */
    @PostMapping("/teacher/sessions/{sessionId}/takeover")
    public ApiResponse<Map<String, Object>> takeoverSession(
            @PathVariable UUID sessionId, Authentication auth) {
        TenantContext ctx = (TenantContext) auth.getDetails();
        UUID userId = (UUID) auth.getPrincipal();
        CounselingSession session = sessionMapper.selectOne(
                new LambdaQueryWrapper<CounselingSession>()
                        .eq(CounselingSession::getTenantId, ctx.tenantId())
                        .eq(CounselingSession::getSessionId, sessionId)
        );
        if (session == null) {
            return ApiResponse.ok(Map.of("success", false, "reason", "session_not_found"));
        }
        // 更新状态为 taken_over
        CounselingSession update = new CounselingSession();
        update.setSessionId(sessionId);
        update.setSessionStatus("taken_over");
        update.setUpdatedAt(java.time.Instant.now());
        sessionMapper.updateById(update);

        auditLogService.log(ctx.tenantId(), userId, "SESSION_TAKEOVER", "session", sessionId, null);
        return ApiResponse.ok(Map.of("success", true, "sessionId", sessionId.toString()));
    }

    /** 生成家长周报链接（7 天有效） */
    @PostMapping("/teacher/students/{studentId}/parent-link")
    public ApiResponse<Map<String, String>> generateParentLink(
            @PathVariable UUID studentId, Authentication auth) {
        TenantContext ctx = (TenantContext) auth.getDetails();
        // 验证学生属于同租户
        User student = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getUserId, studentId)
                        .eq(User::getTenantId, ctx.tenantId())
        );
        if (student == null) {
            return ApiResponse.ok(Map.of("error", "学生不存在"));
        }
        // 生成 parent 类型 JWT（userId=studentId，userType=parent）
        String parentToken = jwtTokenProvider.generateToken(studentId, "parent", ctx.tenantId());
        String link = "/parent?token=" + parentToken;
        return ApiResponse.ok(Map.of("link", link, "expiresIn", "7天"));
    }

    /** 获取学生列表（同租户，班主任仅看本班） */
    @GetMapping("/teacher/students")
    public ApiResponse<List<StudentVO>> getStudents(Authentication auth) {
        TenantContext ctx = (TenantContext) auth.getDetails();
        String classScope = teacherService.resolveClassScope(ctx.tenantId(), ctx.userId(), ctx.userType());
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>()
                .eq(User::getTenantId, ctx.tenantId())
                .eq(User::getUserType, "student")
                .eq(User::getStatus, "active")
                .orderByAsc(User::getGradeCode)
                .orderByAsc(User::getClassCode);
        if (classScope != null) {
            wrapper.eq(User::getClassCode, classScope);
        }
        List<User> students = userMapper.selectList(wrapper);
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

    /** 标记通知为已读 */
    @PutMapping("/teacher/notifications/{id}/read")
    public ApiResponse<Void> markAsRead(@PathVariable UUID id) {
        notificationService.markAsRead(id);
        return ApiResponse.ok(null);
    }

    /** 获取风险事件列表（同租户） */
    @GetMapping("/teacher/risk-events")
    public ApiResponse<List<RiskEvent>> getRiskEvents(
            Authentication auth,
            @RequestParam(defaultValue = "50") int limit) {
        TenantContext ctx = (TenantContext) auth.getDetails();
        List<RiskEvent> events = riskEventMapper.selectList(
                new LambdaQueryWrapper<RiskEvent>()
                        .eq(RiskEvent::getTenantId, ctx.tenantId())
                        .orderByDesc(RiskEvent::getDetectedAt)
                        .last("LIMIT " + Math.min(limit, 100))
        );
        return ApiResponse.ok(events);
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
        List<TeacherService.AlertVO> alerts = teacherService.getAlerts(ctx.tenantId(), null, null, 500);

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

    /** 导出学生列表 CSV */
    @GetMapping("/teacher/export/students")
    public void exportStudents(Authentication auth, HttpServletResponse response) throws IOException {
        TenantContext ctx = (TenantContext) auth.getDetails();
        List<User> students = userMapper.selectList(
                new LambdaQueryWrapper<User>()
                        .eq(User::getTenantId, ctx.tenantId())
                        .eq(User::getUserType, "student")
                        .eq(User::getStatus, "active")
                        .orderByAsc(User::getGradeCode)
                        .orderByAsc(User::getClassCode)
        );

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
            html.append("<tr><td>").append(item.emotion()).append("</td><td>").append(item.count()).append("</td></tr>");
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
            boolean isStudent = "student".equals(msg.senderType());
            html.append("<div class='msg ").append(isStudent ? "student" : "ai").append("'>");
            html.append("<div class='meta'>").append(isStudent ? "🧒 学生" : "🤖 AI");
            if (msg.emotionLabel() != null) html.append(" · ").append(msg.emotionLabel());
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

    // ===== AI-003：质量监控 =====

    /**
     * 质量评分列表（支持筛选：仅低分标记 / 学生 / 分页）
     */
    @GetMapping("/teacher/quality/scores")
    public ApiResponse<Map<String, Object>> getQualityScores(
            Authentication auth,
            @RequestParam(required = false) Boolean flaggedOnly,
            @RequestParam(required = false) UUID studentUserId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        TenantContext ctx = (TenantContext) auth.getDetails();

        var wrapper = new LambdaQueryWrapper<QualityScore>()
                .eq(QualityScore::getTenantId, ctx.tenantId());
        if (Boolean.TRUE.equals(flaggedOnly)) {
            wrapper.eq(QualityScore::getFlagged, true);
        }
        if (studentUserId != null) {
            // 通过 session 关联学生
            var sessions = sessionMapper.selectList(
                    new LambdaQueryWrapper<CounselingSession>()
                            .eq(CounselingSession::getTenantId, ctx.tenantId())
                            .eq(CounselingSession::getStudentUserId, studentUserId)
                            .select(CounselingSession::getSessionId));
            var sessionIds = sessions.stream().map(CounselingSession::getSessionId).toList();
            if (sessionIds.isEmpty()) {
                return ApiResponse.ok(Map.of("items", List.of(), "total", 0, "page", page, "size", size));
            }
            wrapper.in(QualityScore::getSessionId, sessionIds);
        }
        wrapper.orderByDesc(QualityScore::getEvaluatedAt);

        // 简单分页（MyBatis-Plus 无分页插件时用 last LIMIT）
        long total = qualityScoreMapper.selectCount(wrapper);
        wrapper.last("LIMIT " + size + " OFFSET " + (long) (page - 1) * size);
        List<QualityScore> items = qualityScoreMapper.selectList(wrapper);

        // 丰富学生信息（通过 session 查找学生昵称）
        List<Map<String, Object>> enriched = new java.util.ArrayList<>();
        for (QualityScore qs : items) {
            var session2 = sessionMapper.selectById(qs.getSessionId());
            String studentName = null;
            if (session2 != null) {
                var user = userMapper.selectById(session2.getStudentUserId());
                if (user != null) studentName = user.getPseudonym();
            }
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("scoreId", qs.getScoreId());
            row.put("sessionId", qs.getSessionId());
            row.put("studentName", studentName != null ? studentName : "未知");
            row.put("empathyScore", qs.getEmpathyScore() != null ? qs.getEmpathyScore() : 0);
            row.put("cbtCompletion", qs.getCbtCompletion() != null ? qs.getCbtCompletion() : 0);
            row.put("safetyCompliance", qs.getSafetyCompliance() != null ? qs.getSafetyCompliance() : 0);
            row.put("engagementScore", qs.getEngagementScore() != null ? qs.getEngagementScore() : 0);
            row.put("overallScore", qs.getOverallScore() != null ? qs.getOverallScore() : 0);
            row.put("flagged", Boolean.TRUE.equals(qs.getFlagged()));
            row.put("flagReason", qs.getFlagReason() != null ? qs.getFlagReason() : "");
            row.put("evaluatedAt", qs.getEvaluatedAt() != null ? qs.getEvaluatedAt().toString() : "");
            enriched.add(row);
        }

        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("items", enriched);
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        return ApiResponse.ok(result);
    }

    /**
     * AI 质量统计概览（LLM-as-Judge 评分均值 / 低分率）
     */
    @GetMapping("/teacher/quality/ai-stats")
    public ApiResponse<Map<String, Object>> getAiQualityStats(Authentication auth) {
        TenantContext ctx = (TenantContext) auth.getDetails();

        List<QualityScore> all = qualityScoreMapper.selectList(
                new LambdaQueryWrapper<QualityScore>()
                        .eq(QualityScore::getTenantId, ctx.tenantId())
        );

        if (all.isEmpty()) {
            return ApiResponse.ok(Map.of("totalEvaluated", 0, "avgOverall", 0,
                    "flaggedCount", 0, "flagRate", 0.0));
        }

        double avgOverall = all.stream()
                .filter(q -> q.getOverallScore() != null)
                .mapToDouble(q -> q.getOverallScore().doubleValue())
                .average().orElse(0);
        double avgEmpathy = all.stream()
                .filter(q -> q.getEmpathyScore() != null)
                .mapToDouble(q -> q.getEmpathyScore().doubleValue())
                .average().orElse(0);
        double avgSafety = all.stream()
                .filter(q -> q.getSafetyCompliance() != null)
                .mapToDouble(q -> q.getSafetyCompliance().doubleValue())
                .average().orElse(0);
        long flaggedCount = all.stream().filter(q -> Boolean.TRUE.equals(q.getFlagged())).count();

        java.util.Map<String, Object> statsResult = new java.util.LinkedHashMap<>();
        statsResult.put("totalEvaluated", all.size());
        statsResult.put("avgOverall", Math.round(avgOverall * 100.0) / 100.0);
        statsResult.put("avgEmpathy", Math.round(avgEmpathy * 100.0) / 100.0);
        statsResult.put("avgSafety", Math.round(avgSafety * 100.0) / 100.0);
        statsResult.put("flaggedCount", flaggedCount);
        statsResult.put("flagRate", Math.round((double) flaggedCount / all.size() * 100.0) / 100.0);
        return ApiResponse.ok(statsResult);
    }

    /**
     * 会话抽检回放（对话摘要 + 质量评分叠加）
     */
    @GetMapping("/teacher/quality/sessions/{sessionId}/replay")
    public ApiResponse<Map<String, Object>> replaySession(@PathVariable UUID sessionId, Authentication auth) {
        TenantContext ctx = (TenantContext) auth.getDetails();
        auditLogService.log(ctx.tenantId(), (UUID) auth.getPrincipal(), "QUALITY_REPLAY", "counseling_session", sessionId, null);

        // 1. 会话基本信息
        CounselingSession session = sessionMapper.selectById(sessionId);
        if (session == null || !ctx.tenantId().equals(session.getTenantId())) {
            return ApiResponse.ok(Map.of("error", "会话不存在"));
        }

        // 2. 对话摘要（回放内容）
        List<MessageSummary> messages = messageSummaryMapper.selectList(
                new LambdaQueryWrapper<MessageSummary>()
                        .eq(MessageSummary::getTenantId, ctx.tenantId())
                        .eq(MessageSummary::getSessionId, sessionId)
                        .orderByAsc(MessageSummary::getTurnCount)
                        .orderByAsc(MessageSummary::getCreatedAt)
        );

        List<Map<String, Object>> replayMessages = messages.stream().map(m -> Map.<String, Object>of(
                "turn", m.getTurnCount() != null ? m.getTurnCount() : 0,
                "senderType", m.getSenderType() != null ? m.getSenderType() : "unknown",
                "content", m.getContentSummary() != null ? m.getContentSummary() : "",
                "emotionLabel", m.getEmotionLabel() != null ? m.getEmotionLabel() : "",
                "riskLevel", m.getRiskLevel() != null ? m.getRiskLevel() : 0
        )).toList();

        // 3. 质量评分
        QualityScore score = qualityScoreMapper.selectOne(
                new LambdaQueryWrapper<QualityScore>()
                        .eq(QualityScore::getTenantId, ctx.tenantId())
                        .eq(QualityScore::getSessionId, sessionId)
        );

        Map<String, Object> scoreInfo = null;
        if (score != null) {
            scoreInfo = Map.of(
                    "empathyScore", score.getEmpathyScore() != null ? score.getEmpathyScore() : 0,
                    "cbtCompletion", score.getCbtCompletion() != null ? score.getCbtCompletion() : 0,
                    "safetyCompliance", score.getSafetyCompliance() != null ? score.getSafetyCompliance() : 0,
                    "engagementScore", score.getEngagementScore() != null ? score.getEngagementScore() : 0,
                    "overallScore", score.getOverallScore() != null ? score.getOverallScore() : 0,
                    "flagged", Boolean.TRUE.equals(score.getFlagged()),
                    "flagReason", score.getFlagReason() != null ? score.getFlagReason() : ""
            );
        }

        // 4. 学生信息
        String studentName = "未知";
        if (session.getStudentUserId() != null) {
            var user = userMapper.selectById(session.getStudentUserId());
            if (user != null && user.getPseudonym() != null) studentName = user.getPseudonym();
        }

        java.util.Map<String, Object> replayResult = new java.util.LinkedHashMap<>();
        replayResult.put("sessionId", sessionId);
        replayResult.put("studentName", studentName);
        replayResult.put("startedAt", session.getStartedAt() != null ? session.getStartedAt().toString() : "");
        replayResult.put("endedAt", session.getEndedAt() != null ? session.getEndedAt().toString() : "");
        replayResult.put("turnCount", session.getTurnCount() != null ? session.getTurnCount() : 0);
        replayResult.put("sessionSummary", session.getSessionSummary() != null ? session.getSessionSummary() : "");
        replayResult.put("messages", replayMessages);
        replayResult.put("qualityScore", scoreInfo != null ? scoreInfo : Map.of());
        return ApiResponse.ok(replayResult);
    }
}
