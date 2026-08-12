package com.mindsafe.api.gate;

import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.service.consent.GuardianConsentService;
import org.aspectj.lang.JoinPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GuardianConsentGate 切面单元测试（F10：监护人同意门禁 service 层强制）。
 * <p>
 * 直接调用切面方法并模拟 JoinPoint.getArgs()（方法签名约定 args[0]=tenantId、args[1]=studentUserId），
 * 验证：未同意 → CONSENT_REQUIRED；已同意 → 放行；参数异常（null/非 UUID）→ 防御性放行不抛。
 */
class GuardianConsentGateTest {

    private GuardianConsentService guardianConsentService;
    private GuardianConsentGate gate;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID studentUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        guardianConsentService = mock(GuardianConsentService.class);
        gate = new GuardianConsentGate(guardianConsentService);
    }

    private JoinPoint args(Object... values) {
        JoinPoint jp = mock(JoinPoint.class);
        when(jp.getArgs()).thenReturn(values);
        return jp;
    }

    @Test
    @DisplayName("未同意 → 抛 CONSENT_REQUIRED（createSession 入口）")
    void createSession_noConsent_blocked() {
        when(guardianConsentService.hasGuardianConsent(tenantId, studentUserId)).thenReturn(false);

        assertThatThrownBy(() -> gate.gateCreateSession(args(tenantId, studentUserId, "happy", "web")))
                .isExactlyInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.CONSENT_REQUIRED.code());
    }

    @Test
    @DisplayName("未同意 → 抛 CONSENT_REQUIRED（sendMessageStream 4 参入口）")
    void sendMessage_noConsent_blocked() {
        when(guardianConsentService.hasGuardianConsent(tenantId, studentUserId)).thenReturn(false);

        assertThatThrownBy(() -> gate.gateSendMessage(
                args(tenantId, studentUserId, UUID.randomUUID(), "你好")))
                .isExactlyInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.CONSENT_REQUIRED.code());
    }

    @Test
    @DisplayName("未同意 → 抛 CONSENT_REQUIRED（sendMessageStream 6 参入口）")
    void sendMessage6_noConsent_blocked() {
        when(guardianConsentService.hasGuardianConsent(tenantId, studentUserId)).thenReturn(false);

        assertThatThrownBy(() -> gate.gateSendMessage(
                args(tenantId, studentUserId, UUID.randomUUID(), "你好", "anxious", 0.8)))
                .isExactlyInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.CONSENT_REQUIRED.code());
    }

    @Test
    @DisplayName("未同意 → 抛 CONSENT_REQUIRED（sendNudgeStream 入口）")
    void sendNudge_noConsent_blocked() {
        when(guardianConsentService.hasGuardianConsent(tenantId, studentUserId)).thenReturn(false);

        assertThatThrownBy(() -> gate.gateSendNudge(
                args(tenantId, studentUserId, UUID.randomUUID(), 30)))
                .isExactlyInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.CONSENT_REQUIRED.code());
    }

    @Test
    @DisplayName("已同意 → 放行且不抛（三个入口均验证）")
    void withConsent_allEntriesAllowed() {
        when(guardianConsentService.hasGuardianConsent(tenantId, studentUserId)).thenReturn(true);

        assertThatCode(() -> gate.gateCreateSession(args(tenantId, studentUserId, "happy", "web"))).doesNotThrowAnyException();
        assertThatCode(() -> gate.gateSendMessage(args(tenantId, studentUserId, UUID.randomUUID(), "你好"))).doesNotThrowAnyException();
        assertThatCode(() -> gate.gateSendNudge(args(tenantId, studentUserId, UUID.randomUUID(), 30))).doesNotThrowAnyException();

        verify(guardianConsentService, org.mockito.Mockito.times(3))
                .hasGuardianConsent(tenantId, studentUserId);
    }

    @Test
    @DisplayName("参数缺失（args<2）→ 防御性放行，不判同意")
    void insufficientArgs_degradesOpen() {
        assertThatCode(() -> gate.gateCreateSession(args(tenantId))).doesNotThrowAnyException();
        assertThatCode(() -> gate.gateSendMessage(args())).doesNotThrowAnyException();

        verify(guardianConsentService, never()).hasGuardianConsent(any(), any());
    }

    @Test
    @DisplayName("参数为 null → 防御性放行，不判同意（真实链路不会出现）")
    void nullArgs_degradesOpen() {
        assertThatCode(() -> gate.gateCreateSession(args(null, null, "happy", "web"))).doesNotThrowAnyException();

        verify(guardianConsentService, never()).hasGuardianConsent(any(), any());
    }
}
