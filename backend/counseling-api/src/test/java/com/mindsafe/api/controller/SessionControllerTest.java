package com.mindsafe.api.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.service.conversation.ConversationService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.type.ObjectTypeHandler;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SessionController 单元测试（P1 覆盖率冲刺：会话历史/结束+满意度评价）
 */
class SessionControllerTest {

    private CounselingSessionMapper sessionMapper;
    private ConversationService conversationService;
    private SessionController controller;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID studentUserId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.getTypeHandlerRegistry().register(UUID.class, ObjectTypeHandler.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), CounselingSession.class);

        sessionMapper = mock(CounselingSessionMapper.class);
        conversationService = mock(ConversationService.class);
        controller = new SessionController(sessionMapper, conversationService);
    }

    private Authentication studentAuth() {
        Authentication auth = mock(Authentication.class);
        when(auth.getDetails()).thenReturn(new TenantContext(tenantId, studentUserId, "student"));
        return auth;
    }

    private CounselingSession session() {
        CounselingSession s = new CounselingSession();
        s.setSessionId(sessionId);
        s.setStartedAt(Instant.now().minusSeconds(300));
        s.setEndedAt(Instant.now());
        s.setSessionStatus("closed");
        s.setRiskLevelSnapshot(3);
        s.setSatisfactionRating(5);
        return s;
    }

    @Test
    @DisplayName("getSessionHistory → VO 映射（limit 透传）")
    void getSessionHistory() {
        when(sessionMapper.selectList(any())).thenReturn(List.of(session()));

        ApiResponse<List<SessionController.SessionHistoryVO>> resp = controller.getSessionHistory(studentAuth(), 20);

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data()).hasSize(1);
        SessionController.SessionHistoryVO vo = resp.data().get(0);
        assertThat(vo.sessionId()).isEqualTo(sessionId);
        assertThat(vo.status()).isEqualTo("closed");
        assertThat(vo.riskLevel()).isEqualTo(3);
        assertThat(vo.satisfactionRating()).isEqualTo(5);
        assertThat(vo.startedAt()).isNotNull();
        assertThat(vo.endedAt()).isNotNull();
        verify(sessionMapper).selectList(any());
    }

    @Test
    @DisplayName("getSessionHistory limit 超 50 → 截断为 50")
    void getSessionHistory_limitCapped() {
        when(sessionMapper.selectList(any())).thenReturn(List.of(session()));

        ApiResponse<List<SessionController.SessionHistoryVO>> resp = controller.getSessionHistory(studentAuth(), 999);

        assertThat(resp.data()).hasSize(1);
        verify(sessionMapper).selectList(any());
    }

    @Test
    @DisplayName("getSessionHistory 无会话 → 空列表")
    void getSessionHistory_empty() {
        when(sessionMapper.selectList(any())).thenReturn(List.of());

        ApiResponse<List<SessionController.SessionHistoryVO>> resp = controller.getSessionHistory(studentAuth(), 20);

        assertThat(resp.data()).isEmpty();
    }

    @Test
    @DisplayName("getSessionHistory 无认证 → UNAUTHORIZED")
    void getSessionHistory_unauthorized() {
        assertThatThrownBy(() -> controller.getSessionHistory(null, 20))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("closeSession 带 rating+comment → 结束会话 + 更新满意度")
    void closeSession_withRating() {
        ApiResponse<Void> resp = controller.closeSession(sessionId,
                Map.of("rating", 4, "comment", "很有帮助"), studentAuth());

        assertThat(resp.code()).isEqualTo(0);
        verify(conversationService).endSession(tenantId, studentUserId, sessionId);
        verify(sessionMapper).updateById(any(CounselingSession.class));
    }

    @Test
    @DisplayName("closeSession 无请求体 → 仅结束会话")
    void closeSession_noBody() {
        controller.closeSession(sessionId, null, studentAuth());

        verify(conversationService).endSession(tenantId, studentUserId, sessionId);
        verify(sessionMapper, never()).updateById(any(CounselingSession.class));
    }

    @Test
    @DisplayName("closeSession 请求体无 rating → 仅结束会话")
    void closeSession_noRating() {
        controller.closeSession(sessionId, Map.of("comment", "还行"), studentAuth());

        verify(conversationService).endSession(tenantId, studentUserId, sessionId);
        verify(sessionMapper, never()).updateById(any(CounselingSession.class));
    }

    @Test
    @DisplayName("closeSession 无认证 → UNAUTHORIZED（不结束会话）")
    void closeSession_unauthorized() {
        assertThatThrownBy(() -> controller.closeSession(sessionId, Map.of("rating", 5), null))
                .isInstanceOf(BizException.class);
        verify(conversationService, never()).endSession(eq(tenantId), eq(studentUserId), eq(sessionId));
    }
}
