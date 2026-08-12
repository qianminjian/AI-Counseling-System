package com.mindsafe.api.controller;

import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.api.security.SecuritySupport;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.User;
import com.mindsafe.service.audit.AuditLogService;
import com.mindsafe.service.teacher.TeacherService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 教师端预警队列 API（P1-3 审计修复：从 TeacherController 拆分）
 * <p>
 * 职责：预警列表、认领、误报标记、处理完成、回访安排/完成。
 */
@RestController
@RequestMapping("/api/v1")
public class TeacherAlertController {

    private final TeacherService teacherService;
    private final AuditLogService auditLogService;

    public TeacherAlertController(TeacherService teacherService, AuditLogService auditLogService) {
        this.teacherService = teacherService;
        this.auditLogService = auditLogService;
    }

    /** 预警队列（分页/筛选/排序） */
    @GetMapping("/alerts")
    public ApiResponse<List<TeacherService.AlertVO>> getAlerts(
            Authentication auth,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer minLevel,
            @RequestParam(defaultValue = "50") int limit) {
        TenantContext ctx = SecuritySupport.requireContext(auth);
        return ApiResponse.ok(teacherService.getAlerts(ctx.tenantId(), status, minLevel, limit));
    }

    /** 认领预警 */
    @PostMapping("/alerts/{id}/claim")
    public ApiResponse<Void> claimAlert(@PathVariable UUID id, Authentication auth) {
        TenantContext ctx = SecuritySupport.requireContext(auth);
        UUID userId = (UUID) auth.getPrincipal();
        teacherService.claimAlert(ctx.tenantId(), id, userId);
        auditLogService.log(ctx.tenantId(), userId, "ALERT_CLAIM", "risk_event", id, null);
        return ApiResponse.ok(null);
    }

    /** 标记误报 */
    @PatchMapping("/alerts/{id}/false-positive")
    public ApiResponse<Void> markFalsePositive(@PathVariable UUID id, Authentication auth) {
        TenantContext ctx = SecuritySupport.requireContext(auth);
        UUID userId = (UUID) auth.getPrincipal();
        teacherService.markFalsePositive(ctx.tenantId(), id, userId);
        return ApiResponse.ok(null);
    }

    /** 处理完成（线下干预后标记 resolved，可附处理记录） */
    @PostMapping("/alerts/{id}/resolve")
    public ApiResponse<Void> resolveAlert(@PathVariable UUID id,
                                          @RequestBody(required = false) Map<String, String> body,
                                          Authentication auth) {
        TenantContext ctx = SecuritySupport.requireContext(auth);
        UUID userId = (UUID) auth.getPrincipal();
        String note = body != null ? body.get("resolutionNote") : null;
        teacherService.resolveAlert(ctx.tenantId(), id, userId, note);
        auditLogService.log(ctx.tenantId(), userId, "ALERT_RESOLVE", "risk_event", id, note);
        return ApiResponse.ok(null);
    }

    /** 转派预警（design/35 §4.1：重置认领不重置 SLA，目标教师获得新预警） */
    @PostMapping("/alerts/{id}/transfer")
    public ApiResponse<Void> transferAlert(@PathVariable UUID id,
                                           @RequestBody TransferAlertRequest body,
                                           Authentication auth) {
        TenantContext ctx = SecuritySupport.requireContext(auth);
        UUID userId = (UUID) auth.getPrincipal();
        String target = body.targetTeacherId();
        if (target == null || target.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "缺少目标教师 targetTeacherId");
        }
        String note = body.note();
        teacherService.transferAlert(ctx.tenantId(), id, userId, UUID.fromString(target), note);
        auditLogService.log(ctx.tenantId(), userId, "ALERT_TRANSFER", "risk_event", id, target);
        return ApiResponse.ok(null);
    }

    /** 设置学生“已在个案跟踪中”标志（design/35 §4.2 降噪第 3 条：S2/S3 只进时间线） */
    @PutMapping("/teacher/students/{studentId}/case-tracking")
    public ApiResponse<Void> setCaseTracking(@PathVariable UUID studentId,
                                             @RequestBody CaseTrackingRequest body,
                                             Authentication auth) {
        TenantContext ctx = SecuritySupport.requireContext(auth);
        UUID userId = (UUID) auth.getPrincipal();
        boolean enabled = Boolean.TRUE.equals(body.enabled());
        teacherService.setCaseTracking(ctx.tenantId(), studentId, userId, enabled);
        auditLogService.log(ctx.tenantId(), userId, "CASE_TRACKING_SET", User.USER_TYPE_STUDENT, studentId, String.valueOf(enabled));
        return ApiResponse.ok(null);
    }

    /** DATA-004：安排回访（处置后计划回访确认效果） */
    @PostMapping("/alerts/{id}/schedule-followup")
    public ApiResponse<Void> scheduleFollowUp(@PathVariable UUID id,
                                              @RequestBody FollowUpScheduleRequest body,
                                              Authentication auth) {
        TenantContext ctx = SecuritySupport.requireContext(auth);
        UUID userId = (UUID) auth.getPrincipal();
        String followUpAt = body.followUpAt();
        if (followUpAt == null || followUpAt.isBlank()) {
            return ApiResponse.ok(null);
        }
        teacherService.scheduleFollowUp(ctx.tenantId(), id, userId, followUpAt);
        auditLogService.log(ctx.tenantId(), userId, "ALERT_SCHEDULE_FOLLOWUP", "risk_event", id, followUpAt);
        return ApiResponse.ok(null);
    }

    /** DATA-004：完成回访（填写回访记录 + 最终评估） */
    @PostMapping("/alerts/{id}/complete-followup")
    public ApiResponse<Void> completeFollowUp(@PathVariable UUID id,
                                              @RequestBody FollowUpCompleteRequest body,
                                              Authentication auth) {
        TenantContext ctx = SecuritySupport.requireContext(auth);
        UUID userId = (UUID) auth.getPrincipal();
        String note = body.followUpNote();
        String outcome = body.outcome();
        teacherService.completeFollowUp(ctx.tenantId(), id, userId, note, outcome);
        auditLogService.log(ctx.tenantId(), userId, "ALERT_COMPLETE_FOLLOWUP", "risk_event", id, outcome);
        return ApiResponse.ok(null);
    }

    /** DATA-004：待回访列表 */
    @GetMapping("/alerts/pending-followups")
    public ApiResponse<List<Map<String, Object>>> getPendingFollowUps(Authentication auth) {
        TenantContext ctx = SecuritySupport.requireContext(auth);
        var events = teacherService.getPendingFollowUps(ctx.tenantId());
        List<Map<String, Object>> result = events.stream().map(e -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("riskEventId", e.getRiskEventId());
            row.put("studentUserId", e.getStudentUserId());
            row.put("riskType", e.getRiskType());
            row.put("riskLevel", e.getRiskLevel());
            row.put("followUpAt", e.getFollowUpAt() != null ? e.getFollowUpAt().toString() : "");
            row.put("resolutionNote", e.getResolutionNote() != null ? e.getResolutionNote() : "");
            row.put("detectedAt", e.getDetectedAt() != null ? e.getDetectedAt().toString() : "");
            return row;
        }).toList();
        return ApiResponse.ok(result);
    }

    /** S-011③（doing/93）：请求类型化 record（替代 Map 手工解析） */
    public record TransferAlertRequest(String targetTeacherId, String note) {
    }

    public record CaseTrackingRequest(Boolean enabled) {
    }

    public record FollowUpScheduleRequest(String followUpAt) {
    }

    public record FollowUpCompleteRequest(String followUpNote, String outcome) {
    }
}
