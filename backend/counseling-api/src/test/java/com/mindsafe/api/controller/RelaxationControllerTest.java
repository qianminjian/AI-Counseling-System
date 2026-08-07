package com.mindsafe.api.controller;

import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.RelaxationSession;
import com.mindsafe.service.relaxation.RelaxationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RelaxationController 单元测试（T4 批次B 改造版：SQL 下沉 RelaxationService，Controller 仅 HTTP 层职责）
 * <p>
 * 覆盖：练习列表 / 记录参数解析与默认值 / 今日计数。
 * 域语义（插入 / 租户条件计数）由 RelaxationService 测试覆盖。
 */
class RelaxationControllerTest {

    private RelaxationService relaxationService;
    private RelaxationController controller;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID studentUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        relaxationService = mock(RelaxationService.class);
        controller = new RelaxationController(relaxationService);
    }

    private Authentication studentAuth() {
        Authentication auth = mock(Authentication.class);
        when(auth.getDetails()).thenReturn(new TenantContext(tenantId, studentUserId, "student"));
        return auth;
    }

    private RelaxationSession session(String exerciseType, int durationSeconds, boolean completed) {
        RelaxationSession s = RelaxationSession.create(tenantId, studentUserId, exerciseType, durationSeconds, completed);
        return s;
    }

    @Test
    @DisplayName("getExercises → 5 个内置练习")
    void getExercises() {
        ApiResponse<List<RelaxationController.ExerciseVO>> resp = controller.getExercises();

        assertThat(resp.data()).hasSize(5);
        assertThat(resp.data().get(0).id()).isEqualTo("breathing_323");
        assertThat(resp.data().get(4).category()).isEqualTo("somatic");
    }

    @Test
    @DisplayName("recordSession 显式参数 → 透传服务并返回会话")
    void recordSession_explicit() {
        RelaxationSession session = session("body_scan", 120, false);
        when(relaxationService.recordSession(tenantId, studentUserId, "body_scan", 120, false))
                .thenReturn(session);

        ApiResponse<RelaxationSession> resp = controller.recordSession(
                Map.of("exerciseType", "body_scan", "durationSeconds", 120, "completed", false),
                studentAuth());

        assertThat(resp.code()).isEqualTo(0);
        RelaxationSession returned = resp.data();
        assertThat(returned.getTenantId()).isEqualTo(tenantId);
        assertThat(returned.getStudentUserId()).isEqualTo(studentUserId);
        assertThat(returned.getExerciseType()).isEqualTo("body_scan");
        assertThat(returned.getDurationSeconds()).isEqualTo(120);
        assertThat(returned.getCompleted()).isFalse();
        assertThat(returned.getRelaxationId()).isNotNull();
        verify(relaxationService).recordSession(tenantId, studentUserId, "body_scan", 120, false);
    }

    @Test
    @DisplayName("recordSession 空请求体 → 默认值（breathing_323/60s/true）")
    void recordSession_defaults() {
        RelaxationSession session = session("breathing_323", 60, true);
        when(relaxationService.recordSession(tenantId, studentUserId, "breathing_323", 60, true))
                .thenReturn(session);

        ApiResponse<RelaxationSession> resp = controller.recordSession(Map.of(), studentAuth());

        RelaxationSession returned = resp.data();
        assertThat(returned.getExerciseType()).isEqualTo("breathing_323");
        assertThat(returned.getDurationSeconds()).isEqualTo(60);
        assertThat(returned.getCompleted()).isTrue();
        verify(relaxationService).recordSession(tenantId, studentUserId, "breathing_323", 60, true);
    }

    @Test
    @DisplayName("recordSession 无认证 → UNAUTHORIZED")
    void recordSession_unauthorized() {
        assertThatThrownBy(() -> controller.recordSession(Map.of(), null))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("recordSession details 非 TenantContext → UNAUTHORIZED")
    void recordSession_badDetails() {
        Authentication auth = mock(Authentication.class);
        when(auth.getDetails()).thenReturn("not-a-context");

        assertThatThrownBy(() -> controller.recordSession(Map.of(), auth))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("getTodayCount → 今日完成计数（Service 统计）")
    void getTodayCount() {
        when(relaxationService.countTodayCompleted(tenantId, studentUserId)).thenReturn(3L);

        ApiResponse<Map<String, Object>> resp = controller.getTodayCount(studentAuth());

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data().get("count")).isEqualTo(3L);
        verify(relaxationService).countTodayCompleted(eq(tenantId), eq(studentUserId));
    }

    @Test
    @DisplayName("getTodayCount 无认证 → UNAUTHORIZED")
    void getTodayCount_unauthorized() {
        assertThatThrownBy(() -> controller.getTodayCount(null))
                .isInstanceOf(BizException.class);
    }
}
