package com.mindsafe.api.controller;

import com.mindsafe.api.dto.teacher.AddNoteRequest;
import com.mindsafe.api.dto.teacher.TransitionCaseRequest;
import com.mindsafe.api.dto.vo.RiskEventVO;
import com.mindsafe.api.dto.vo.TeacherNoteVO;
import com.mindsafe.api.render.CsvExportWriter;
import com.mindsafe.api.render.SessionExportRenderer;
import com.mindsafe.api.render.WeeklyReportRenderer;
import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.api.security.SecuritySupport;
import com.mindsafe.api.security.JwtTokenProvider;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.CounselingSession;
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
        TenantContext ctx = SecuritySupport.requireContext(auth);
        return ApiResponse.ok(teacherService.getDashboard(ctx.tenantId(), ctx.userId()));
    }

    /** 数据看板统计（风隩分布/班级对比/会话趋势/情绪分布） */
    @GetMapping("/teacher/stats")
    public ApiResponse<TeacherService.StatsVO> getStats(Authentication auth) {
        TenantContext ctx = SecuritySupport.requireContext(auth);
        String classScope = teacherService.resolveClassScope(ctx.tenantId(), ctx.userId(), ctx.userType());
        return ApiResponse.ok(teacherService.getStats(ctx.tenantId(), classScope));
    }
    
    /** 满意度统计（平均评分/分布/近 7 天趋势） */
    @GetMapping("/teacher/satisfaction")
    public ApiResponse<TeacherService.SatisfactionStatsVO> getSatisfaction(Authentication auth) {
        TenantContext ctx = SecuritySupport.requireContext(auth);
        return ApiResponse.ok(teacherService.getSatisfactionStats(ctx.tenantId()));
    }

    // ===== 干预话术模板 =====

    /** 获取干预话术模板列表（R-7：模板下沉 service 层维护，见 TeacherService.TEMPLATES） */
    @GetMapping("/teacher/templates")
    public ApiResponse<List<Map<String, String>>> getTemplates() {
        return ApiResponse.ok(teacherService.getTemplates());
    }

    // ===== 学生管理 =====

    /** 高风险学生列表 */
    @GetMapping("/teacher/students/high-risk")
    public ApiResponse<List<TeacherService.HighRiskStudentVO>> getHighRiskStudents(Authentication auth) {
        TenantContext ctx = SecuritySupport.requireContext(auth);
        String classScope = teacherService.resolveClassScope(ctx.tenantId(), ctx.userId(), ctx.userType());
        return ApiResponse.ok(teacherService.getHighRiskStudents(ctx.tenantId(), classScope));
    }

    /** 学生档案（对话摘要/预警历史/备注） */
    @GetMapping("/teacher/students/{id}")
    public ApiResponse<TeacherService.StudentProfileVO> getStudentProfile(
            @PathVariable UUID id, Authentication auth) {
        TenantContext ctx = SecuritySupport.requireContext(auth);
        return ApiResponse.ok(teacherService.getStudentProfile(ctx.tenantId(), id, ctx.userType()));
    }

    /** 学生画像雷达图（PROF-004，6 维度 + 里程碑） */
    @GetMapping("/teacher/students/{id}/radar")
    public ApiResponse<Map<String, Object>> getStudentRadar(
            @PathVariable UUID id, Authentication auth) {
        TenantContext ctx = SecuritySupport.requireContext(auth);
        return ApiResponse.ok(profileRadarService.getRadarData(ctx.tenantId(), id));
    }

    /** 添加备注（F11：请求体类型化为 AddNoteRequest；F9：响应收敛为 TeacherNoteVO） */
    @PostMapping("/teacher/students/{id}/notes")
    public ApiResponse<TeacherNoteVO> addNote(
            @PathVariable UUID id,
            @RequestBody AddNoteRequest body,
            Authentication auth) {
        TenantContext ctx = SecuritySupport.requireContext(auth);
        TeacherNoteVO vo = TeacherNoteVO.from(teacherService.addNote(
                ctx.tenantId(), id, ctx.userId(), body.content(), body.noteType()));
        return ApiResponse.ok(vo);
    }

    /** 查看某次会话的对话摘要（教师端） */
    @GetMapping("/teacher/sessions/{sessionId}/messages")
    public ApiResponse<List<TeacherService.MessageSummaryVO>> getSessionMessages(
            @PathVariable UUID sessionId, Authentication auth) {
        TenantContext ctx = SecuritySupport.requireContext(auth);
        return ApiResponse.ok(teacherService.getSessionMessages(ctx.tenantId(), sessionId));
    }

    /** 获取会话 AI 摘要（教师端） */
    @GetMapping("/teacher/sessions/{sessionId}/summary")
    public ApiResponse<Map<String, Object>> getSessionSummary(
            @PathVariable UUID sessionId, Authentication auth) {
        TenantContext ctx = SecuritySupport.requireContext(auth);
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
        TenantContext ctx = SecuritySupport.requireContext(auth);
        // T4 批次A/B：归属校验 + 状态更新 + 审计整体下沉 TeacherService（事务内）
        TeacherService.TakeoverResult result = teacherService.takeoverSession(ctx.tenantId(), ctx.userId(), sessionId);
        if (!result.success()) {
            return ApiResponse.ok(Map.of("success", false, "reason", result.reason()));
        }
        return ApiResponse.ok(Map.of("success", true, "sessionId", sessionId.toString()));
    }

    /** 生成家长周报链接（7 天有效） */
    @PostMapping("/teacher/students/{studentId}/parent-link")
    public ApiResponse<Map<String, String>> generateParentLink(
            @PathVariable UUID studentId, Authentication auth) {
        TenantContext ctx = SecuritySupport.requireContext(auth);
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

    /** 获取学生列表（同租户，班主任仅看本班；T4 批次C：查询下沉 TeacherService）
     * BUG-UI-03：改用 listVisibleStudents——冻结（withdrawn）学生须可见并带状态标识
     * BUG-T-04-03（2026-08-12）：年级/班级筛选 + 昵称搜索 + 风险等级列 */
    @GetMapping("/teacher/students")
    public ApiResponse<List<StudentVO>> getStudents(
            Authentication auth,
            @RequestParam(required = false) String gradeCode,
            @RequestParam(required = false) String classCode,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer minRisk) {
        // doing/93 S-011①：认证上下文提取统一单点（缺失/类型不符 → 401 而非 500）
        TenantContext ctx = SecuritySupport.requireContext(auth);
        String classScope = teacherService.resolveClassScope(ctx.tenantId(), ctx.userId(), ctx.userType());
        List<User> students = teacherService.listVisibleStudents(ctx.tenantId(), classScope, gradeCode, classCode, keyword);
        // 风险等级批量关联（open/claimed 预警 ∪ 会话快照，避免 N+1）
        Map<UUID, Integer> riskByStudent = teacherService.batchStudentMaxRisk(ctx.tenantId(), students);
        List<StudentVO> voList = students.stream()
                .filter(s -> minRisk == null || (riskByStudent.getOrDefault(s.getUserId(), 0) >= minRisk))
                .map(s -> new StudentVO(s.getUserId(), s.getPseudonym(), s.getGradeCode(), s.getClassCode(),
                        s.getStatus(), riskByStudent.getOrDefault(s.getUserId(), 0)))
                .toList();
        return ApiResponse.ok(voList);
    }

    // ===== 通知（保留 M1 接口） =====

    /** 获取当前教师的通知列表（BUG-T-06-02/03：状态筛选 + 分页 + 学生昵称） */
    @GetMapping("/teacher/notifications")
    public ApiResponse<Map<String, Object>> getNotifications(
            Authentication auth,
            @RequestParam(defaultValue = "ALL") String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        // F1：原直接强转 principal（无 requireContext）——非法认证 → 500；统一收口后 401
        TenantContext ctx = SecuritySupport.requireContext(auth);
        NotificationService.NotificationPage pageResult = notificationService.getNotifications(ctx.userId(), status, page, size);
        return ApiResponse.ok(Map.of("items", pageResult.items(), "total", pageResult.total()));
    }

    /** 获取未读通知数量 */
    @GetMapping("/teacher/notifications/unread-count")
    public ApiResponse<Long> getUnreadCount(Authentication auth) {
        TenantContext ctx = SecuritySupport.requireContext(auth);
        return ApiResponse.ok(notificationService.countUnread(ctx.userId()));
    }

    /** 标记通知为已读（P1 审计修复：携带收件人 ID，防 IDOR） */
    @PutMapping("/teacher/notifications/{id}/read")
    public ApiResponse<Void> markAsRead(@PathVariable UUID id, Authentication auth) {
        TenantContext ctx = SecuritySupport.requireContext(auth);
        notificationService.markAsRead(id, ctx.userId());
        return ApiResponse.ok(null);
    }

    /** 获取风险事件列表（同租户；T4 批次C：分页查询下沉 TeacherService；F9：响应收敛为 RiskEventVO） */
    @GetMapping("/teacher/risk-events")
    public ApiResponse<List<RiskEventVO>> getRiskEvents(
            Authentication auth,
            @RequestParam(defaultValue = "50") int limit) {
        TenantContext ctx = SecuritySupport.requireContext(auth);
        List<RiskEventVO> voList = teacherService.pageRiskEvents(ctx.tenantId(), limit).stream()
                .map(RiskEventVO::from)
                .toList();
        return ApiResponse.ok(voList);
    }

    // ===== 数据导出 =====

    /** 预警导出上限（B-01：导出路径独立上限，与列表 100 钳制解耦；超限显式提示截断） */
    private static final int EXPORT_ALERTS_HARD_LIMIT = 5000;

    /** 导出预警记录 CSV（F8：渲染整体下沉 CsvExportWriter） */
    @GetMapping("/teacher/export/alerts")
    public void exportAlerts(Authentication auth, HttpServletResponse response) throws IOException {
        TenantContext ctx = SecuritySupport.requireContext(auth);
        auditLogService.log(ctx.tenantId(), ctx.userId(), "EXPORT_ALERTS", "export");
        // P1 审计修复：导出跟随数据范围（班主任仅导出本班，不再全校可见）
        // B-01：导出路径独立上限 5000（不再被列表 100 钳制静默截断；M1：limit 固定传上限值，截断提示方可达）
        String classScope = teacherService.resolveClassScope(ctx.tenantId(), ctx.userId(), ctx.userType());
        List<TeacherService.AlertVO> alerts = teacherService.getAlertsForExport(ctx.tenantId(), classScope, null, null, EXPORT_ALERTS_HARD_LIMIT);

        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=alerts_export.csv");
        // F8：BOM/转义/截断提示/行渲染全部下沉 CsvExportWriter（先取 Writer 再写 BOM，避免混用抛 IllegalStateException）
        CsvExportWriter.writeAlerts(response.getWriter(), alerts, EXPORT_ALERTS_HARD_LIMIT);
    }

    /** 导出学生列表 CSV（T4 批次C：查询下沉 TeacherService，与 getStudents 共用 DRY；F8：渲染下沉 CsvExportWriter） */
    @GetMapping("/teacher/export/students")
    public void exportStudents(Authentication auth, HttpServletResponse response) throws IOException {
        TenantContext ctx = SecuritySupport.requireContext(auth);
        // P1 审计修复：导出跟随数据范围（班主任仅导出本班，不再全校可见）
        String classScope = teacherService.resolveClassScope(ctx.tenantId(), ctx.userId(), ctx.userType());
        List<User> students = teacherService.listActiveStudents(ctx.tenantId(), classScope);
        // F9：渲染层仅接收 StudentRow（不含实体，杜绝 User 流入渲染层）
        List<CsvExportWriter.StudentRow> rows = students.stream()
                .map(s -> new CsvExportWriter.StudentRow(s.getPseudonym(), s.getGradeCode(), s.getClassCode(), s.getStatus()))
                .toList();

        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=students_export.csv");
        CsvExportWriter.writeStudents(response.getWriter(), rows);
    }

    /** 学生视图对象（BUG-UI-03：+ status 账号状态；BUG-T-04-03：+ riskLevel 风险等级列） */
    public record StudentVO(UUID userId, String displayName, String gradeCode, String classCode, String status, int riskLevel) {}

    // ===== 个案管理（WB-003，design/35 M3） =====

    /** 个案阶段推进（建案→评估→干预跟踪→结案，P1 审计修复：读取真实当前阶段并持久化；F11：请求体类型化） */
    @PostMapping("/teacher/cases/{studentId}/transition")
    public ApiResponse<Map<String, Object>> transitionCase(
            @PathVariable UUID studentId,
            @RequestBody TransitionCaseRequest body,
            Authentication auth) {
        TenantContext ctx = SecuritySupport.requireContext(auth);
        String targetStage = body.targetStage();

        // P1 审计修复：非法阶段值 → 400 参数错误（不再 500）
        CaseLifecycleService.CaseStage target;
        try {
            target = CaseLifecycleService.CaseStage.valueOf(targetStage);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "非法个案阶段: " + targetStage);
        }

        CaseLifecycleService.StageTransition result =
                teacherService.transitionCaseStage(ctx.tenantId(), studentId, ctx.userId(), target);

        auditLogService.log(ctx.tenantId(), ctx.userId(), "CASE_TRANSITION", User.USER_TYPE_STUDENT, studentId, targetStage);
        return ApiResponse.ok(Map.of(
                "allowed", result.allowed(),
                "newStage", result.to().name(),
                "reason", result.reason() != null ? result.reason() : ""));
    }

    // ===== 周报导出（可打印 HTML） =====

    /** 生成周报告 HTML（教师浏览器 Ctrl+P 保存 PDF） */
    @GetMapping(value = "/teacher/report/weekly", produces = "text/html; charset=UTF-8")
    public void weeklyReport(Authentication auth, HttpServletResponse response) throws IOException {
        TenantContext ctx = SecuritySupport.requireContext(auth);
        auditLogService.log(ctx.tenantId(), ctx.userId(), "EXPORT_WEEKLY_REPORT", "report");

        // P1 审计修复：周报跟随数据范围（班主任仅统计本班，不再全校可见）
        String classScope = teacherService.resolveClassScope(ctx.tenantId(), ctx.userId(), ctx.userType());
        var stats = teacherService.getStats(ctx.tenantId(), classScope);
        String now = java.time.LocalDate.now().toString();

        // F8：HTML 模板/转义/情绪翻译整体下沉 WeeklyReportRenderer（B-04 防 XSS 语义保留）
        String html = WeeklyReportRenderer.render(stats, now);
        response.setContentType("text/html; charset=UTF-8");
        response.getWriter().write(html);
        response.getWriter().flush();
    }

    /** 单会话导出（个案存档，可打印 HTML） */
    @GetMapping(value = "/teacher/sessions/{sessionId}/export", produces = "text/html; charset=UTF-8")
    public void exportSession(@PathVariable UUID sessionId, Authentication auth, HttpServletResponse response) throws IOException {
        TenantContext ctx = SecuritySupport.requireContext(auth);
        auditLogService.log(ctx.tenantId(), ctx.userId(), "EXPORT_SESSION", "counseling_session", sessionId, null);

        var messages = teacherService.getSessionMessages(ctx.tenantId(), sessionId);
        String exportedAt = java.time.LocalDateTime.now().toString().substring(0, 16);

        // F8：HTML 模板/转义/情绪翻译整体下沉 SessionExportRenderer（B-04 防 XSS 语义保留）
        String html = SessionExportRenderer.render(sessionId, messages, exportedAt);
        response.setContentType("text/html; charset=UTF-8");
        response.getWriter().write(html);
        response.getWriter().flush();
    }
}
