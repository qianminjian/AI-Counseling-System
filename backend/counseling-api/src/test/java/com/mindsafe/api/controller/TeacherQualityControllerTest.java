package com.mindsafe.api.controller;

import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.service.audit.AuditLogService;
import com.mindsafe.service.teacher.TeacherQualityService;
import com.mindsafe.service.teacher.TeacherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TeacherQualityController 单元测试（C3 重构后：HTTP 编排验证）
 * <p>
 * C3（2026-08-05）：查询/聚合逻辑下沉 TeacherQualityService 后，controller 测试
 * 收敛为验证「参数透传 + 结果包装 + 审计日志」；行为细节由 TeacherQualityServiceTest 覆盖。
 * 覆盖：
 * - getFlaggedSessions / getQualityScores / getAiQualityStats 透传
 * - getQualityStats flagRate 计算 + totalRated=0 兜底（controller 仍持有该编排）
 * - replaySession 审计日志 + 会话不存在 error 分支
 */
class TeacherQualityControllerTest {

    private TeacherService teacherService;
    private TeacherQualityService teacherQualityService;
    private AuditLogService auditLogService;
    private TeacherQualityController controller;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID teacherUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        teacherService = mock(TeacherService.class);
        teacherQualityService = mock(TeacherQualityService.class);
        auditLogService = mock(AuditLogService.class);
        controller = new TeacherQualityController(teacherService, teacherQualityService, auditLogService);
    }

    private Authentication teacherAuth() {
        Authentication a = mock(Authentication.class);
        when(a.getPrincipal()).thenReturn(teacherUserId);
        when(a.getDetails()).thenReturn(new TenantContext(tenantId, teacherUserId, "psych_teacher"));
        return a;
    }

    // ===== getFlaggedSessions =====

    @Test
    @DisplayName("getFlaggedSessions 透传 service 低分列表")
    void flaggedSessions() {
        Map<String, Object> row = Map.of("sessionId", UUID.randomUUID(), "rating", 1);
        when(teacherQualityService.flaggedSessions(tenantId)).thenReturn(List.of(row));

        ApiResponse<List<Map<String, Object>>> resp = controller.getFlaggedSessions(teacherAuth());

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data()).hasSize(1);
        assertThat(resp.data().get(0).get("rating")).isEqualTo(1);
        verify(teacherQualityService).flaggedSessions(tenantId);
    }

    // ===== getQualityStats =====

    @Test
    @DisplayName("getQualityStats 计算低分计数与 flagRate（1 星 2 星计入）")
    void qualityStats() {
        when(teacherService.getSatisfactionStats(tenantId))
                .thenReturn(new TeacherService.SatisfactionStatsVO(10, 3.5,
                        List.of(
                                new TeacherService.RatingDistItem(1, 1L),
                                new TeacherService.RatingDistItem(2, 2L),
                                new TeacherService.RatingDistItem(3, 3L),
                                new TeacherService.RatingDistItem(4, 2L),
                                new TeacherService.RatingDistItem(5, 2L)),
                        5, 4.0));

        ApiResponse<Map<String, Object>> resp = controller.getQualityStats(teacherAuth());

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data().get("totalRated")).isEqualTo(10L);
        assertThat(resp.data().get("flaggedCount")).isEqualTo(3L);
        assertThat(resp.data().get("flagRate")).isEqualTo(30.0);
        assertThat(resp.data().get("recentAvg")).isEqualTo(4.0);
    }

    @Test
    @DisplayName("getQualityStats totalRated=0 → flagRate=0 不除零")
    void qualityStats_zeroRated() {
        when(teacherService.getSatisfactionStats(tenantId))
                .thenReturn(new TeacherService.SatisfactionStatsVO(0, 0.0,
                        List.of(new TeacherService.RatingDistItem(1, 0L),
                                new TeacherService.RatingDistItem(2, 0L),
                                new TeacherService.RatingDistItem(3, 0L),
                                new TeacherService.RatingDistItem(4, 0L),
                                new TeacherService.RatingDistItem(5, 0L)),
                        0, 0.0));

        ApiResponse<Map<String, Object>> resp = controller.getQualityStats(teacherAuth());

        assertThat(resp.data().get("flagRate")).isEqualTo(0.0);
    }

    // ===== getQualityScores =====

    @Test
    @DisplayName("getQualityScores 透传筛选参数与分页")
    void qualityScores_passthrough() {
        UUID studentId = UUID.randomUUID();
        when(teacherQualityService.qualityScores(tenantId, true, studentId, 2, 50))
                .thenReturn(Map.of("items", List.of(), "total", 0L, "page", 2, "size", 50));

        ApiResponse<Map<String, Object>> resp = controller.getQualityScores(teacherAuth(), true, studentId, 2, 50);

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data().get("total")).isEqualTo(0L);
        assertThat(resp.data().get("page")).isEqualTo(2);
        verify(teacherQualityService).qualityScores(tenantId, true, studentId, 2, 50);
    }

    // ===== getAiQualityStats =====

    @Test
    @DisplayName("getAiQualityStats 透传 AI 统计结果")
    void aiStats_passthrough() {
        when(teacherQualityService.aiQualityStats(tenantId))
                .thenReturn(Map.of("totalEvaluated", 2, "avgOverall", 87.5, "flagRate", 50.0));

        ApiResponse<Map<String, Object>> resp = controller.getAiQualityStats(teacherAuth());

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data().get("totalEvaluated")).isEqualTo(2);
        assertThat(resp.data().get("avgOverall")).isEqualTo(87.5);
        assertThat(resp.data().get("flagRate")).isEqualTo(50.0);
    }

    // ===== replaySession =====

    @Test
    @DisplayName("replaySession service 返回 null（会话不存在/跨租户）→ error 提示")
    void replay_notFound() {
        UUID sessionId = UUID.randomUUID();
        when(teacherQualityService.replaySession(tenantId, sessionId)).thenReturn(null);

        ApiResponse<Map<String, Object>> resp = controller.replaySession(sessionId, teacherAuth());

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data().get("error")).isEqualTo("会话不存在");
    }

    @Test
    @DisplayName("replaySession 成功：审计日志 + 透传回放结果")
    void replay_success() {
        UUID sessionId = UUID.randomUUID();
        when(teacherQualityService.replaySession(tenantId, sessionId))
                .thenReturn(Map.of("sessionId", sessionId, "studentName", "小星"));

        ApiResponse<Map<String, Object>> resp = controller.replaySession(sessionId, teacherAuth());

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data().get("studentName")).isEqualTo("小星");
        verify(auditLogService).log(tenantId, teacherUserId, "QUALITY_REPLAY",
                "counseling_session", sessionId, null);
    }
}
