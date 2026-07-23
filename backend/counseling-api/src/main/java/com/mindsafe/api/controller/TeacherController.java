package com.mindsafe.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.domain.entity.Notification;
import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.RiskEventMapper;
import com.mindsafe.domain.mapper.UserMapper;
import com.mindsafe.service.notification.NotificationService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * 教师端 API（M1：预警通知 + 风险事件 + 学生列表）
 */
@RestController
@RequestMapping("/api/v1/teacher")
public class TeacherController {

    private final NotificationService notificationService;
    private final RiskEventMapper riskEventMapper;
    private final UserMapper userMapper;

    public TeacherController(NotificationService notificationService,
                             RiskEventMapper riskEventMapper,
                             UserMapper userMapper) {
        this.notificationService = notificationService;
        this.riskEventMapper = riskEventMapper;
        this.userMapper = userMapper;
    }

    /** 获取当前教师的通知列表 */
    @GetMapping("/notifications")
    public ApiResponse<List<Notification>> getNotifications(
            Authentication auth,
            @RequestParam(defaultValue = "50") int limit) {
        UUID userId = (UUID) auth.getPrincipal();
        return ApiResponse.ok(notificationService.getNotifications(userId, limit));
    }

    /** 获取未读通知数量 */
    @GetMapping("/notifications/unread-count")
    public ApiResponse<Long> getUnreadCount(Authentication auth) {
        UUID userId = (UUID) auth.getPrincipal();
        return ApiResponse.ok(notificationService.countUnread(userId));
    }

    /** 标记通知为已读 */
    @PutMapping("/notifications/{id}/read")
    public ApiResponse<Void> markAsRead(@PathVariable UUID id) {
        notificationService.markAsRead(id);
        return ApiResponse.ok(null);
    }

    /** 获取风险事件列表（同租户） */
    @GetMapping("/risk-events")
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

    /** 获取学生列表（同租户） */
    @GetMapping("/students")
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

    /** 学生视图对象 */
    public record StudentVO(UUID userId, String displayName, String gradeCode, String classCode) {}
}
