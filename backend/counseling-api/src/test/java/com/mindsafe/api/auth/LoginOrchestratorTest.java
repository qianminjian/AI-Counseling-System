package com.mindsafe.api.auth;

import com.mindsafe.api.security.BusinessAuthProvider;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.common.tenant.TenantContextHolder;
import com.mindsafe.domain.entity.User;
import com.mindsafe.service.audit.AuditLogService;
import com.mindsafe.service.auth.AuthUserService;
import com.mindsafe.service.auth.LoginLockoutService;
import com.mindsafe.service.auth.PasswordPolicyService;
import com.mindsafe.service.auth.TenantAccessGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LoginOrchestrator 单元测试（doing/93 S-001：登录编排单点）。
 * <p>
 * 覆盖 8 步链固定顺序：锁定→候选（重名拒绝）→withdrawn 冻结→密码匹配→租户门禁→清计数→留痕→签发，
 * 以及签发审计的租户上下文绑定/清除、mustChangePassword 判定。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("登录编排单点（S-001）")
class LoginOrchestratorTest {

    @Mock private LoginLockoutService lockoutService;
    @Mock private AuthUserService authUserService;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private TenantAccessGuard tenantAccessGuard;
    @Mock private BusinessAuthProvider businessAuthProvider;
    @Mock private PasswordPolicyService passwordPolicyService;
    @Mock private AuditLogService auditLogService;

    private LoginOrchestrator orchestrator;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private static final String ACCESS = "access-token";
    private static final String REFRESH = "refresh-token";

    @BeforeEach
    void setUp() {
        orchestrator = new LoginOrchestrator(
                lockoutService, authUserService, passwordEncoder, tenantAccessGuard,
                businessAuthProvider, passwordPolicyService, auditLogService);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    private User activeStudent() {
        User u = new User();
        u.setUserId(userId);
        u.setTenantId(tenantId);
        u.setPseudonym("小星");
        u.setUserType("student");
        u.setPasswordHash("hash");
        u.setPasswordChangedAt(Instant.now());
        return u;
    }

    private void mockIssue() {
        when(businessAuthProvider.issueAccessToken(userId, "student", tenantId)).thenReturn(ACCESS);
        when(businessAuthProvider.issueRefreshToken(userId, "student", tenantId)).thenReturn(REFRESH);
    }

    @Test
    @DisplayName("成功链：锁定→候选→密码→门禁→清计数→留痕→签发+审计（租户上下文绑定后清除）")
    void loginWithPassword_successChain() {
        User user = activeStudent();
        when(authUserService.findLoginCandidates("小星")).thenReturn(List.of(user));
        when(passwordEncoder.matches("pwd", "hash")).thenReturn(true);
        when(tenantAccessGuard.isLoginAllowed(tenantId)).thenReturn(true);
        when(passwordPolicyService.isExpired(user.getPasswordChangedAt())).thenReturn(false);
        mockIssue();

        LoginOrchestrator.LoginSession session = orchestrator.loginWithPassword("小星", "pwd");

        verify(lockoutService).checkLockout("小星");
        verify(lockoutService).clearFailures("小星");
        verify(authUserService).recordLoginSuccess(tenantId, userId);
        verify(auditLogService).log(eq(tenantId), eq(userId), eq("LOGIN"), eq("user"), eq(userId), org.mockito.ArgumentMatchers.isNull());
        assertThat(session.accessToken()).isEqualTo(ACCESS);
        assertThat(session.mustChangePassword()).isFalse();
        // 审计提交后租户上下文必须清除（防线程池复用串租户）
        assertThat(TenantContextHolder.get()).isNull();
    }

    @Test
    @DisplayName("昵称重名（>1 候选）→ recordFailure + UNAUTHORIZED（防 LIMIT 1 随机命中）")
    void duplicatePseudonym_rejected() {
        when(authUserService.findLoginCandidates("小星"))
                .thenReturn(List.of(activeStudent(), activeStudent()));

        assertThatThrownBy(() -> orchestrator.loginWithPassword("小星", "pwd"))
                .isExactlyInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.UNAUTHORIZED.code());
        verify(lockoutService).recordFailure("小星");
        verify(authUserService, never()).recordLoginSuccess(any(), any());
    }

    @Test
    @DisplayName("withdrawn 冻结 → FORBIDDEN 专属提示，不记失败")
    void withdrawn_frozen() {
        User withdrawn = activeStudent();
        withdrawn.setStatus(User.STATUS_WITHDRAWN);
        when(authUserService.findLoginCandidates("小星")).thenReturn(List.of(withdrawn));

        assertThatThrownBy(() -> orchestrator.loginWithPassword("小星", "pwd"))
                .isExactlyInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.FORBIDDEN.code());
        verify(lockoutService, never()).recordFailure(anyString());
    }

    @Test
    @DisplayName("密码错误 → recordFailure + UNAUTHORIZED，不清计数")
    void wrongPassword_recorded() {
        User user = activeStudent();
        when(authUserService.findLoginCandidates("小星")).thenReturn(List.of(user));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> orchestrator.loginWithPassword("小星", "wrong"))
                .isExactlyInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.UNAUTHORIZED.code());
        verify(lockoutService).recordFailure("小星");
        verify(lockoutService, never()).clearFailures(anyString());
    }

    @Test
    @DisplayName("租户门禁拒绝（suspended）→ FORBIDDEN，不清计数不留痕")
    void tenantSuspended_rejected() {
        User user = activeStudent();
        when(authUserService.findLoginCandidates("小星")).thenReturn(List.of(user));
        when(passwordEncoder.matches("pwd", "hash")).thenReturn(true);
        when(tenantAccessGuard.isLoginAllowed(tenantId)).thenReturn(false);

        assertThatThrownBy(() -> orchestrator.loginWithPassword("小星", "pwd"))
                .isExactlyInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.FORBIDDEN.code());
        verify(lockoutService, never()).clearFailures(anyString());
        verify(authUserService, never()).recordLoginSuccess(any(), any());
    }

    @Test
    @DisplayName("锁定检查抛出 → 直接传播（RATE_LIMITED），不进入后续步骤")
    void lockout_propagates() {
        org.mockito.Mockito.doThrow(new BizException(ErrorCode.RATE_LIMITED, "登录失败次数过多"))
                .when(lockoutService).checkLockout("小星");

        assertThatThrownBy(() -> orchestrator.loginWithPassword("小星", "pwd"))
                .isExactlyInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.RATE_LIMITED.code());
        verify(authUserService, never()).findLoginCandidates(anyString());
    }

    @Test
    @DisplayName("mustChangePassword=true 或密码过期 → mustChangePassword=true")
    void mustChangePassword_flag() {
        User user = activeStudent();
        user.setMustChangePassword(true);
        when(authUserService.findLoginCandidates("小星")).thenReturn(List.of(user));
        when(passwordEncoder.matches("pwd", "hash")).thenReturn(true);
        when(tenantAccessGuard.isLoginAllowed(tenantId)).thenReturn(true);
        mockIssue();

        LoginOrchestrator.LoginSession session = orchestrator.loginWithPassword("小星", "pwd");

        assertThat(session.mustChangePassword()).isTrue();
    }

    @Test
    @DisplayName("issueLoginSession：签发 + 审计（租户上下文绑定后清除）")
    void issueLoginSession_auditsWithTenantContext() {
        User user = activeStudent();
        when(passwordPolicyService.isExpired(user.getPasswordChangedAt())).thenReturn(false);
        mockIssue();

        LoginOrchestrator.LoginSession session = orchestrator.issueLoginSession(user, "VOICE_LOGIN");

        verify(auditLogService).log(tenantId, userId, "VOICE_LOGIN", "user", userId, null);
        assertThat(session.refreshToken()).isEqualTo(REFRESH);
        assertThat(TenantContextHolder.get()).isNull();
    }
}
