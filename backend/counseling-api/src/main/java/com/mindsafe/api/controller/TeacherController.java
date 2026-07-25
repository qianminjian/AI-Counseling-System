package com.mindsafe.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.domain.entity.Notification;
import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.entity.TeacherNote;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.RiskEventMapper;
import com.mindsafe.domain.mapper.UserMapper;
import com.mindsafe.service.notification.NotificationService;
import com.mindsafe.service.teacher.TeacherService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

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
    private final RiskEventMapper riskEventMapper;
    private final UserMapper userMapper;

    public TeacherController(NotificationService notificationService,
                             TeacherService teacherService,
                             RiskEventMapper riskEventMapper,
                             UserMapper userMapper) {
        this.notificationService = notificationService;
        this.teacherService = teacherService;
        this.riskEventMapper = riskEventMapper;
        this.userMapper = userMapper;
    }

    // ===== 工作台 =====

    /** 工作台概览（待处理预警数/今日任务/周趋势） */
    @GetMapping("/teacher/dashboard")
    public ApiResponse<TeacherService.DashboardVO> getDashboard(Authentication auth) {
        TenantContext ctx = (TenantContext) auth.getDetails();
        UUID userId = (UUID) auth.getPrincipal();
        return ApiResponse.ok(teacherService.getDashboard(ctx.tenantId(), userId));
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
        UUID userId = (UUID) auth.getPrincipal();
        teacherService.claimAlert(id, userId);
        return ApiResponse.ok(null);
    }

    /** 标记误报 */
    @PatchMapping("/alerts/{id}/false-positive")
    public ApiResponse<Void> markFalsePositive(@PathVariable UUID id, Authentication auth) {
        UUID userId = (UUID) auth.getPrincipal();
        teacherService.markFalsePositive(id, userId);
        return ApiResponse.ok(null);
    }

    // ===== 学生管理 =====

    /** 高风险学生列表 */
    @GetMapping("/teacher/students/high-risk")
    public ApiResponse<List<TeacherService.HighRiskStudentVO>> getHighRiskStudents(Authentication auth) {
        TenantContext ctx = (TenantContext) auth.getDetails();
        return ApiResponse.ok(teacherService.getHighRiskStudents(ctx.tenantId()));
    }

    /** 学生档案（对话摘要/预警历史/备注） */
    @GetMapping("/teacher/students/{id}")
    public ApiResponse<TeacherService.StudentProfileVO> getStudentProfile(
            @PathVariable UUID id, Authentication auth) {
        TenantContext ctx = (TenantContext) auth.getDetails();
        return ApiResponse.ok(teacherService.getStudentProfile(ctx.tenantId(), id));
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

    /** 获取学生列表（同租户） */
    @GetMapping("/teacher/students")
    public ApiResponse<List<StudentVO>> getStudents(Authentication auth) {
        TenantContext ctx = (TenantContext) auth.getDetails();
        List<User> students = userMapper.selectList(
                new LambdaQueryWrapper<User>()
                        .eq(User::getTenantId, ctx.tenantId())
                        .eq(User::getUserType, "student")
                        .eq(User::getStatus, "active")
                        .orderByAsc(User::getGradeCode)
                        .orderByAsc(User::getClassCode)
        );
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

    /** 学生视图对象 */
    public record StudentVO(UUID userId, String displayName, String gradeCode, String classCode) {}
}
