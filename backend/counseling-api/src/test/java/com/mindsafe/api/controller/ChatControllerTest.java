package com.mindsafe.api.controller;

import com.mindsafe.api.dto.chat.CreateSessionRequest;
import com.mindsafe.api.dto.chat.NudgeRequest;
import com.mindsafe.api.dto.chat.SendMessageRequest;
import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.service.conversation.ConversationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Flux;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ChatController 单元测试（对话编排；F10 后门禁已下沉 GuardianConsentGate 切面，门禁行为见 GuardianConsentGateTest）。
 * <p>
 * 覆盖：createSession / sendMessage / sendNudge 正常放行至 ConversationService；
 * 旧关闭接口 TTL 语义（到期 410 / 配置非法防御降级 / endSession 不受门禁限制）。
 */
class ChatControllerTest {

    private ConversationService conversationService;
    private ChatController controller;
    private Authentication authentication;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID studentId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        conversationService = mock(ConversationService.class);
        // ARCH-010 D5：旧关闭接口 TTL 默认空配置 = 未到期（保留可用）
        // F10：GuardianConsentService 已从 controller 构造器移除（门禁下沉切面）
        controller = new ChatController(conversationService, "");

        authentication = mock(Authentication.class);
        when(authentication.getDetails()).thenReturn(new TenantContext(tenantId, studentId, "student"));
    }

    @Nested
    @DisplayName("对话入口放行（门禁由 GuardianConsentGate 切面强制，controller 仅编排）")
    class ConversationEntry {

        @Test
        @DisplayName("createSession 放行至服务层")
        void createSessionAllowed() {
            controller.createSession(new CreateSessionRequest("happy", "web"), authentication);

            verify(conversationService).createSession(tenantId, studentId, "happy", "web");
        }

        @Test
        @DisplayName("sendMessage 放行（纯文本走无语音重载）")
        void sendMessageAllowed() {
            when(conversationService.sendMessageStream(eq(tenantId), eq(studentId), eq(sessionId), anyString()))
                    .thenReturn(Flux.empty());

            controller.sendMessage(sessionId,
                    new SendMessageRequest("你好", null, null, "text", null, null), authentication);

            verify(conversationService).sendMessageStream(tenantId, studentId, sessionId, "你好");
        }

        @Test
        @DisplayName("sendMessage 放行（带语音情绪走 6 参重载）")
        void sendMessageWithVoiceEmotionAllowed() {
            when(conversationService.sendMessageStream(eq(tenantId), eq(studentId), eq(sessionId), anyString(),
                    anyString(), eq(0.8)))
                    .thenReturn(Flux.empty());

            controller.sendMessage(sessionId,
                    new SendMessageRequest("你好", "anxious", 0.8, "voice", null, null), authentication);

            verify(conversationService).sendMessageStream(tenantId, studentId, sessionId, "你好", "anxious", 0.8);
        }

        @Test
        @DisplayName("sendNudge 放行")
        void sendNudgeAllowed() {
            when(conversationService.sendNudgeStream(eq(tenantId), eq(studentId), eq(sessionId), org.mockito.ArgumentMatchers.anyInt()))
                    .thenReturn(Flux.empty());

            controller.sendNudge(sessionId, new NudgeRequest(30), authentication);

            verify(conversationService).sendNudgeStream(tenantId, studentId, sessionId, 30);
        }
    }

    @Test
    @DisplayName("endSession 不受同意门禁限制（清理操作放行）")
    void endSessionNotGated() {
        controller.endSession(sessionId, authentication);

        verify(conversationService).endSession(tenantId, studentId, sessionId);
    }

    @Test
    @DisplayName("ARCH-010 D5：旧关闭接口 TTL 到期 → 410 拒绝且不触达对话服务")
    void endSessionTtlExpiredGone() {
        ChatController expiredController = new ChatController(conversationService, "2000-01-01T00:00:00Z");

        assertThatThrownBy(() -> expiredController.endSession(sessionId, authentication))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCode.API_GONE.code());
        verifyNoInteractions(conversationService);
    }

    @Test
    @DisplayName("ARCH-010 D5：expires-at 配置非法 → 视为未到期（防御降级，不拒绝请求）")
    void endSessionInvalidExpiresAtDegradesOpen() {
        ChatController invalidController = new ChatController(conversationService, "not-a-date");

        controller = invalidController;
        controller.endSession(sessionId, authentication);

        verify(conversationService).endSession(tenantId, studentId, sessionId);
    }
}
