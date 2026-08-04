package com.mindsafe.api.controller;

import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.service.analytics.DataAnalyticsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DataAnalyticsController 单元测试（P1 覆盖率冲刺：干预效果/成长轨迹/校级报告）
 */
class DataAnalyticsControllerTest {

    private DataAnalyticsService analyticsService;
    private DataAnalyticsController controller;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID teacherUserId = UUID.randomUUID();
    private final UUID studentUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        analyticsService = mock(DataAnalyticsService.class);
        controller = new DataAnalyticsController(analyticsService);
    }

    private Authentication teacherAuth() {
        Authentication auth = mock(Authentication.class);
        when(auth.getDetails()).thenReturn(new TenantContext(tenantId, teacherUserId, "psych_teacher"));
        return auth;
    }

    @Test
    @DisplayName("interventionEffect → 日期解析 + 透传（windowDays 默认 30）")
    void interventionEffect_defaultWindow() {
        Map<String, Object> result = Map.of("delta", 0.15);
        when(analyticsService.interventionEffect(tenantId, studentUserId, LocalDate.of(2026, 3, 1), 30))
                .thenReturn(result);

        ApiResponse<Map<String, Object>> resp = controller.interventionEffect(
                teacherAuth(), studentUserId, "2026-03-01", 30);

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data()).isEqualTo(result);
        verify(analyticsService).interventionEffect(tenantId, studentUserId, LocalDate.of(2026, 3, 1), 30);
    }

    @Test
    @DisplayName("interventionEffect 显式 windowDays → 透传")
    void interventionEffect_explicitWindow() {
        when(analyticsService.interventionEffect(tenantId, studentUserId, LocalDate.of(2026, 3, 1), 90))
                .thenReturn(Map.of());

        controller.interventionEffect(teacherAuth(), studentUserId, "2026-03-01", 90);

        verify(analyticsService).interventionEffect(tenantId, studentUserId, LocalDate.of(2026, 3, 1), 90);
    }

    @Test
    @DisplayName("growthTrajectory → 学期起止日期解析 + 透传")
    void growthTrajectory() {
        Map<String, Object> result = Map.of("milestones", 3);
        when(analyticsService.growthTrajectory(tenantId, studentUserId,
                LocalDate.of(2026, 2, 15), LocalDate.of(2026, 7, 10)))
                .thenReturn(result);

        ApiResponse<Map<String, Object>> resp = controller.growthTrajectory(
                teacherAuth(), studentUserId, "2026-02-15", "2026-07-10");

        assertThat(resp.data()).isEqualTo(result);
        verify(analyticsService).growthTrajectory(tenantId, studentUserId,
                LocalDate.of(2026, 2, 15), LocalDate.of(2026, 7, 10));
    }

    @Test
    @DisplayName("schoolReport → 期间日期解析 + 透传")
    void schoolReport() {
        Map<String, Object> result = Map.of("totalSessions", 120);
        when(analyticsService.schoolReport(tenantId, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)))
                .thenReturn(result);

        ApiResponse<Map<String, Object>> resp = controller.schoolReport(
                teacherAuth(), "2026-03-01", "2026-03-31");

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data()).isEqualTo(result);
        verify(analyticsService).schoolReport(tenantId, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
    }

    @Test
    @DisplayName("interventionEffect 非法日期 → DateTimeParseException")
    void interventionEffect_badDate() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        controller.interventionEffect(teacherAuth(), studentUserId, "2026/03/01", 30))
                .isInstanceOf(java.time.format.DateTimeParseException.class);
    }
}
