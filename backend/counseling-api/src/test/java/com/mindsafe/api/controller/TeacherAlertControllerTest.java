package com.mindsafe.api.controller;

import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.service.audit.AuditLogService;
import com.mindsafe.service.teacher.TeacherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TeacherAlertController 单元测试（P1 覆盖率冲刺：预警队列/认领/误报/转派/回访）
 */
class TeacherAlertControllerTest {

    private TeacherService teacherService;
    private AuditLogService auditLogService;
    private TeacherAlertController controller;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID teacherUserId = UUID.randomUUID();
    private final UUID alertId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        teacherService = mock(TeacherService.class);
        auditLogService = mock(AuditLogService.class);
        controller = new TeacherAlertController(teacherService, auditLogService);
    }

    private Authentication auth() {
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(teacherUserId);
        when(auth.getDetails()).thenReturn(new TenantContext(tenantId, teacherUserId, "psych_teacher"));
        return auth;
    }

    @Test
    @DisplayName("getAlerts 透传筛选参数（默认 limit 50）")
    void getAlerts() {
        when(teacherService.getAlerts(tenantId, "open", 3, 50)).thenReturn(List.of(
                new TeacherService.AlertVO(alertId, UUID.randomUUID(), "小星", "self_harm", 3,
                        "open", Instant.now(), null, false)));

        ApiResponse<List<TeacherService.AlertVO>> resp = controller.getAlerts(auth(), "open", 3, 50);

        assertThat(resp.data()).hasSize(1);
        assertThat(resp.data().get(0).riskType()).isEqualTo("self_harm");
    }

    @Test
    @DisplayName("claimAlert 认领 + 审计")
    void claimAlert() {
        var resp = controller.claimAlert(alertId, auth());

        assertThat(resp.code()).isEqualTo(0);
        verify(teacherService).claimAlert(tenantId, alertId, teacherUserId);
        verify(auditLogService).log(tenantId, teacherUserId, "ALERT_CLAIM", "risk_event", alertId, null);
    }

    @Test
    @DisplayName("markFalsePositive 误报标记（无审计）")
    void markFalsePositive() {
        var resp = controller.markFalsePositive(alertId, auth());

        assertThat(resp.code()).isEqualTo(0);
        verify(teacherService).markFalsePositive(tenantId, alertId, teacherUserId);
        verify(auditLogService, never()).log(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("resolveAlert 附处理记录 + 审计")
    void resolveAlert_withNote() {
        var resp = controller.resolveAlert(alertId, Map.of("resolutionNote", "已谈话"), auth());

        assertThat(resp.code()).isEqualTo(0);
        verify(teacherService).resolveAlert(tenantId, alertId, teacherUserId, "已谈话");
        verify(auditLogService).log(tenantId, teacherUserId, "ALERT_RESOLVE", "risk_event", alertId, "已谈话");
    }

    @Test
    @DisplayName("resolveAlert 无请求体 → note=null")
    void resolveAlert_noBody() {
        controller.resolveAlert(alertId, null, auth());

        verify(teacherService).resolveAlert(tenantId, alertId, teacherUserId, null);
        verify(auditLogService).log(tenantId, teacherUserId, "ALERT_RESOLVE", "risk_event", alertId, null);
    }

    @Test
    @DisplayName("transferAlert 缺 targetTeacherId → 参数异常")
    void transferAlert_missingTarget() {
        assertThatThrownBy(() -> controller.transferAlert(alertId, Map.of(), auth()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(teacherService, never()).transferAlert(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("transferAlert 成功 → 转派 + 审计")
    void transferAlert_success() {
        UUID targetTeacher = UUID.randomUUID();
        var resp = controller.transferAlert(alertId,
                Map.of("targetTeacherId", targetTeacher.toString(), "note", "请跟进"), auth());

        assertThat(resp.code()).isEqualTo(0);
        verify(teacherService).transferAlert(tenantId, alertId, teacherUserId, targetTeacher, "请跟进");
        verify(auditLogService).log(tenantId, teacherUserId, "ALERT_TRANSFER", "risk_event", alertId,
                targetTeacher.toString());
    }

    @Test
    @DisplayName("setCaseTracking enabled=true + 审计")
    void setCaseTracking_on() {
        var resp = controller.setCaseTracking(UUID.randomUUID(), Map.of("enabled", true), auth());

        assertThat(resp.code()).isEqualTo(0);
        verify(teacherService).setCaseTracking(any(), any(), any(), org.mockito.ArgumentMatchers.eq(true));
        verify(auditLogService).log(any(), any(), org.mockito.ArgumentMatchers.eq("CASE_TRACKING_SET"),
                org.mockito.ArgumentMatchers.eq("student"), any(), org.mockito.ArgumentMatchers.eq("true"));
    }

    @Test
    @DisplayName("setCaseTracking enabled=false + 审计")
    void setCaseTracking_off() {
        controller.setCaseTracking(UUID.randomUUID(), Map.of("enabled", false), auth());

        verify(teacherService).setCaseTracking(any(), any(), any(), org.mockito.ArgumentMatchers.eq(false));
    }

    @Test
    @DisplayName("scheduleFollowUp 缺 followUpAt → 静默跳过")
    void scheduleFollowUp_missing() {
        var resp = controller.scheduleFollowUp(alertId, Map.of(), auth());

        assertThat(resp.code()).isEqualTo(0);
        verify(teacherService, never()).scheduleFollowUp(any(), any(), any(), any());
    }

    @Test
    @DisplayName("scheduleFollowUp 成功 + 审计")
    void scheduleFollowUp_success() {
        var resp = controller.scheduleFollowUp(alertId, Map.of("followUpAt", "2026-08-05T10:00:00Z"), auth());

        assertThat(resp.code()).isEqualTo(0);
        verify(teacherService).scheduleFollowUp(tenantId, alertId, teacherUserId, "2026-08-05T10:00:00Z");
        verify(auditLogService).log(tenantId, teacherUserId, "ALERT_SCHEDULE_FOLLOWUP", "risk_event", alertId,
                "2026-08-05T10:00:00Z");
    }

    @Test
    @DisplayName("completeFollowUp 回访记录 + 审计 outcome")
    void completeFollowUp() {
        var resp = controller.completeFollowUp(alertId,
                Map.of("followUpNote", "情绪稳定", "outcome", "improved"), auth());

        assertThat(resp.code()).isEqualTo(0);
        verify(teacherService).completeFollowUp(tenantId, alertId, teacherUserId, "情绪稳定", "improved");
        verify(auditLogService).log(tenantId, teacherUserId, "ALERT_COMPLETE_FOLLOWUP", "risk_event", alertId,
                "improved");
    }

    @Test
    @DisplayName("getPendingFollowUps → 待回访列表（null 字段兜底）")
    void getPendingFollowUps() {
        RiskEvent e = new RiskEvent();
        e.setRiskEventId(alertId);
        e.setStudentUserId(UUID.randomUUID());
        e.setRiskType("self_harm");
        e.setRiskLevel(3);
        e.setFollowUpAt(Instant.now());
        e.setResolutionNote(null);
        e.setDetectedAt(Instant.now());
        when(teacherService.getPendingFollowUps(tenantId)).thenReturn(List.of(e));

        var resp = controller.getPendingFollowUps(auth());

        assertThat(resp.data()).hasSize(1);
        assertThat(resp.data().get(0).get("riskEventId")).isEqualTo(alertId);
        assertThat(resp.data().get(0).get("resolutionNote")).isEqualTo("");
        assertThat(resp.data().get(0).get("followUpAt")).isNotEqualTo("");
    }
}
