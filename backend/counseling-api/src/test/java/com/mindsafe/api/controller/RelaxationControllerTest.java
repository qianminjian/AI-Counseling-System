package com.mindsafe.api.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.RelaxationSession;
import com.mindsafe.domain.mapper.RelaxationSessionMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.type.ObjectTypeHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RelaxationController 单元测试（P1 覆盖率冲刺：放松练习列表/记录/今日计数）
 */
class RelaxationControllerTest {

    private RelaxationSessionMapper relaxationSessionMapper;
    private RelaxationController controller;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID studentUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.getTypeHandlerRegistry().register(UUID.class, ObjectTypeHandler.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), RelaxationSession.class);

        relaxationSessionMapper = mock(RelaxationSessionMapper.class);
        controller = new RelaxationController(relaxationSessionMapper);
    }

    private Authentication studentAuth() {
        Authentication auth = mock(Authentication.class);
        when(auth.getDetails()).thenReturn(new TenantContext(tenantId, studentUserId, "student"));
        return auth;
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
    @DisplayName("recordSession 显式参数 → 持久化并返回会话")
    void recordSession_explicit() {
        ApiResponse<RelaxationSession> resp = controller.recordSession(
                Map.of("exerciseType", "body_scan", "durationSeconds", 120, "completed", false),
                studentAuth());

        assertThat(resp.code()).isEqualTo(0);
        RelaxationSession session = resp.data();
        assertThat(session.getTenantId()).isEqualTo(tenantId);
        assertThat(session.getStudentUserId()).isEqualTo(studentUserId);
        assertThat(session.getExerciseType()).isEqualTo("body_scan");
        assertThat(session.getDurationSeconds()).isEqualTo(120);
        assertThat(session.getCompleted()).isFalse();
        assertThat(session.getRelaxationId()).isNotNull();
        verify(relaxationSessionMapper).insert(session);
    }

    @Test
    @DisplayName("recordSession 空请求体 → 默认值（breathing_323/60s/true）")
    void recordSession_defaults() {
        ApiResponse<RelaxationSession> resp = controller.recordSession(Map.of(), studentAuth());

        RelaxationSession session = resp.data();
        assertThat(session.getExerciseType()).isEqualTo("breathing_323");
        assertThat(session.getDurationSeconds()).isEqualTo(60);
        assertThat(session.getCompleted()).isTrue();
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
    @DisplayName("getTodayCount → 今日完成计数（filter 透传）")
    void getTodayCount() {
        when(relaxationSessionMapper.selectCount(any())).thenReturn(3L);

        ApiResponse<Map<String, Object>> resp = controller.getTodayCount(studentAuth());

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data().get("count")).isEqualTo(3L);
        verify(relaxationSessionMapper).selectCount(any());
    }

    @Test
    @DisplayName("getTodayCount 无认证 → UNAUTHORIZED")
    void getTodayCount_unauthorized() {
        assertThatThrownBy(() -> controller.getTodayCount(null))
                .isInstanceOf(BizException.class);
    }
}
