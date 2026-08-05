package com.mindsafe.api.controller;

import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.api.security.JwtTokenProvider;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.domain.mapper.MessageSummaryMapper;
import com.mindsafe.domain.mapper.RiskEventMapper;
import com.mindsafe.domain.mapper.UserMapper;
import com.mindsafe.service.audit.AuditLogService;
import com.mindsafe.service.casemanage.CaseLifecycleService;
import com.mindsafe.service.notification.NotificationService;
import com.mindsafe.service.profile.ProfileRadarService;
import com.mindsafe.service.security.FieldEncryptionService;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TeacherController 单元测试（P1 审计修复接线验证）
 * <p>
 * 覆盖：
 * - markAsRead 携带收件人 principal（IDOR 防越权接线）
 * - transitionCase 非法阶段值 → PARAM_INVALID（不再 500），合法值调用服务层并返回当前阶段
 * - exportAlerts / getStats 跟随数据范围（班主任 scope 透传）
 */
class TeacherControllerTest {

    private NotificationService notificationService;
    private TeacherService teacherService;
    private AuditLogService auditLogService;
    private TeacherController controller;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID teacherUserId = UUID.randomUUID();
    private final UUID studentUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        teacherService = mock(TeacherService.class);
        auditLogService = mock(AuditLogService.class);
        controller = new TeacherController(
                notificationService,
                teacherService,
                mock(ProfileRadarService.class),
                mock(RiskEventMapper.class),
                mock(UserMapper.class),
                mock(CounselingSessionMapper.class),
                mock(MessageSummaryMapper.class),
                auditLogService,
                mock(JwtTokenProvider.class),
                mock(FieldEncryptionService.class));
    }

    private Authentication teacherAuth(String userType) {
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(teacherUserId);
        when(auth.getDetails()).thenReturn(new TenantContext(tenantId, teacherUserId, userType));
        return auth;
    }

    // ===== ① markAsRead 归属接线（防 IDOR） =====

    @Test
    @DisplayName("markAsRead 将当前登录用户作为收件人传给服务（防他人通知被标记）")
    void markAsRead_passesPrincipalAsRecipient() {
        UUID notificationId = UUID.randomUUID();

        controller.markAsRead(notificationId, teacherAuth("psych_teacher"));

        verify(notificationService).markAsRead(notificationId, teacherUserId);
    }

    // ===== ② transitionCase 伪 API 修复接线 =====

    @Test
    @DisplayName("transitionCase 非法阶段值 → BizException PARAM_INVALID（不再 500），不触达服务层")
    void transitionCase_invalidStage_returns400() {
        when(teacherService.transitionCaseStage(any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("非法个案阶段: NOT_A_STAGE"));

        assertThatThrownBy(() -> controller.transitionCase(
                studentUserId,
                Map.of("targetStage", "NOT_A_STAGE"),
                teacherAuth("psych_teacher")))
                .isExactlyInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.PARAM_INVALID.code());
        verify(teacherService, never()).transitionCaseStage(any(), any(), any(), any());
    }

    @Test
    @DisplayName("transitionCase 合法阶段 → 调用服务层并返回推进结果")
    void transitionCase_validStage_callsService() {
        CaseLifecycleService.StageTransition transition = new CaseLifecycleService.StageTransition(
                true, CaseLifecycleService.CaseStage.INTAKE,
                CaseLifecycleService.CaseStage.ASSESSMENT, null);
        when(teacherService.transitionCaseStage(tenantId, studentUserId, teacherUserId,
                CaseLifecycleService.CaseStage.ASSESSMENT)).thenReturn(transition);

        var resp = controller.transitionCase(
                studentUserId,
                Map.of("targetStage", "ASSESSMENT"),
                teacherAuth("psych_teacher"));

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data().get("allowed")).isEqualTo(true);
        assertThat(resp.data().get("newStage")).isEqualTo("ASSESSMENT");
        verify(auditLogService).log(tenantId, teacherUserId, "CASE_TRANSITION", "student", studentUserId, "ASSESSMENT");
    }

    // ===== ③ 数据范围接线（班主任 scope 透传） =====

    @Test
    @DisplayName("exportAlerts 班主任 → getAlerts 携带本班 scope（不再全校）")
    void exportAlerts_classTeacher_passesScope() throws IOException {
        when(teacherService.resolveClassScope(tenantId, teacherUserId, "class_teacher")).thenReturn("CLASS_1");
        when(teacherService.getAlerts(any(), any(), any(), any(), anyInt())).thenReturn(List.of());
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getOutputStream()).thenReturn(mock(ServletOutputStream.class));
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        controller.exportAlerts(teacherAuth("class_teacher"), response);

        verify(teacherService).getAlerts(tenantId, "CLASS_1", null, null, 500);
    }

    @Test
    @DisplayName("exportAlerts 心理老师 → getAlerts 全校（scope=null）")
    void exportAlerts_psychTeacher_passesNullScope() throws IOException {
        when(teacherService.resolveClassScope(tenantId, teacherUserId, "psych_teacher")).thenReturn(null);
        when(teacherService.getAlerts(any(), any(), any(), any(), anyInt())).thenReturn(List.of());
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getOutputStream()).thenReturn(mock(ServletOutputStream.class));
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        controller.exportAlerts(teacherAuth("psych_teacher"), response);

        verify(teacherService).getAlerts(tenantId, null, null, null, 500);
    }

    @Test
    @DisplayName("getStats 班主任 → getStats 携带本班 scope")
    void getStats_classTeacher_passesScope() {
        when(teacherService.resolveClassScope(tenantId, teacherUserId, "class_teacher")).thenReturn("CLASS_1");
        when(teacherService.getStats(any(), anyString())).thenReturn(new TeacherService.StatsVO(
                List.of(), List.of(), List.of(), List.of()));

        controller.getStats(teacherAuth("class_teacher"));

        verify(teacherService).getStats(tenantId, "CLASS_1");
    }
}
