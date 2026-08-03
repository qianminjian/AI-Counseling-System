package com.mindsafe.api.controller;

import com.mindsafe.api.dto.chat.CreateSessionRequest;
import com.mindsafe.api.dto.chat.NudgeRequest;
import com.mindsafe.api.dto.chat.SendMessageRequest;
import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.service.consent.GuardianConsentService;
import com.mindsafe.service.conversation.ConversationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Flux;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ChatController 单元测试（R-03 监护人同意门禁）
 * <p>
 * 覆盖：createSession / sendMessage / sendNudge 三个对话入口在缺少监护人同意时
 * 抛 CONSENT_REQUIRED 且不触达 ConversationService；已同意时正常放行。
 */
class ChatControllerTest {

    private ConversationService conversationService;
    private GuardianConsentService guardianConsentService;
    private ChatController controller;
    private Authentication authentication;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID studentId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        conversationService = mock(ConversationService.class);
        guardianConsentService = mock(GuardianConsentService.class);
        controller = new ChatController(conversationService, guardianConsentService);

        authentication = mock(Authentication.class);
        when(authentication.getDetails()).thenReturn(new TenantContext(tenantId, studentId, "student"));
    }

    @Nested
    @DisplayName("缺少监护人同意 → CONSENT_REQUIRED，且不触达对话服务")
    class ConsentMissing {

        @BeforeEach
        void noConsent() {
            when(guardianConsentService.hasGuardianConsent(tenantId, studentId)).thenReturn(false);
        }

        @Test
        @DisplayName("createSession 被门禁拦截")
        void createSessionBlocked() {
            assertThatThrownBy(() -> controller.createSession(
                    new CreateSessionRequest("happy", "web"), authentication))
                    .isInstanceOf(BizException.class)
                    .extracting("code").isEqualTo(ErrorCode.CONSENT_REQUIRED.code());
            verifyNoInteractions(conversationService);
        }

        @Test
        @DisplayName("sendMessage 被门禁拦截")
        void sendMessageBlocked() {
            assertThatThrownBy(() -> controller.sendMessage(sessionId,
                    new SendMessageRequest("你好", null, null, "text", null, null), authentication))
                    .isInstanceOf(BizException.class)
                    .extracting("code").isEqualTo(ErrorCode.CONSENT_REQUIRED.code());
            verifyNoInteractions(conversationService);
        }

        @Test
        @DisplayName("sendNudge 被门禁拦截")
        void sendNudgeBlocked() {
            assertThatThrownBy(() -> controller.sendNudge(sessionId,
                    new NudgeRequest(30), authentication))
                    .isInstanceOf(BizException.class)
                    .extracting("code").isEqualTo(ErrorCode.CONSENT_REQUIRED.code());
            verifyNoInteractions(conversationService);
        }
    }

    @Nested
    @DisplayName("已获监护人同意 → 正常放行至对话服务")
    class ConsentPresent {

        @BeforeEach
        void withConsent() {
            when(guardianConsentService.hasGuardianConsent(tenantId, studentId)).thenReturn(true);
        }

        @Test
        @DisplayName("createSession 放行")
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
        @DisplayName("sendNudge 放行")
        void sendNudgeAllowed() {
            when(conversationService.sendNudgeStream(eq(tenantId), eq(studentId), eq(sessionId), anyInt()))
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
        verify(guardianConsentService, never()).hasGuardianConsent(any(), any());
    }
}
