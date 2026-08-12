package com.mindsafe.api.controller;

import com.mindsafe.api.auth.AuthenticatedUser;
import com.mindsafe.api.auth.LoginOrchestrator;
import com.mindsafe.api.auth.TrialAuthStrategy;
import com.mindsafe.api.auth.TrialRegisterRequest;
import com.mindsafe.api.controller.AuthController.ChangePasswordRequest;
import com.mindsafe.api.controller.AuthController.GuardianConsentConfirmRequest;
import com.mindsafe.api.controller.AuthController.GuardianConsentRequest;
import com.mindsafe.api.controller.AuthController.LoginRequest;
import com.mindsafe.api.controller.AuthController.LoginResponse;
import com.mindsafe.api.controller.AuthController.LogoutRequest;
import com.mindsafe.api.controller.AuthController.PinLoginRequest;
import com.mindsafe.api.controller.AuthController.RefreshRequest;
import com.mindsafe.api.controller.AuthController.SetPinRequest;
import com.mindsafe.api.controller.AuthController.TrialRegisterResponse;
import com.mindsafe.api.controller.AuthController.VoiceLoginRequest;
import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.api.security.JwtTokenProvider;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.common.tenant.TenantContextHolder;
import com.mindsafe.domain.entity.User;
import com.mindsafe.service.audit.AuditLogService;
import com.mindsafe.service.auth.AuthUserService;
import com.mindsafe.service.consent.GuardianConsentService;
import com.mindsafe.service.auth.LoginLockoutService;
import com.mindsafe.service.auth.PasswordPolicyService;
import com.mindsafe.service.auth.TenantAccessGuard;
import com.mindsafe.service.auth.TokenBlacklistService;
import com.mindsafe.service.auth.TrialAuthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuthController 单元测试（P1 覆盖率冲刺：登录/注册/改密/PIN/声纹/刷新/登出/监护人同意）
 * <p>
 * 覆盖：
 * - login 成功（双 token + 审计 + 租户上下文绑定）、密码错误、昵称重名拒绝、租户门禁
 * - trialRegister 成功 + age<14 无同意 → guardianConsentPending
 * - changePassword / setPin / issueVoiceCredential 的 TenantContext 缺失 → UNAUTHORIZED
 * - pinLogin 成功 / 失败锁定
 * - voiceLogin 凭证无效 / 黑名单 / 账号不可用 / 成功
 * - refresh 成功（旧 token 拉黑防重放）/ 无效 / 黑名单
 * - logout 双 token 拉黑 + 审计
 * - guardian-consent request / confirm
 */
class AuthControllerTest {

    private AuthUserService authUserService;
    private PasswordEncoder passwordEncoder;
    private JwtTokenProvider jwtTokenProvider;
    private com.mindsafe.api.security.BusinessAuthProvider businessAuthProvider;
    private TrialAuthStrategy trialAuthStrategy;
    private TrialAuthService trialAuthService;
    private AuditLogService auditLogService;
    private LoginLockoutService lockoutService;
    private PasswordPolicyService passwordPolicyService;
    private GuardianConsentService guardianConsentService;
    private TokenBlacklistService tokenBlacklistService;
    private TenantAccessGuard tenantAccessGuard;
    private LoginOrchestrator loginOrchestrator;
    private AuthController controller;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private static final String ACCESS_TOKEN = "access-token";
    private static final String REFRESH_TOKEN = "refresh-token";
    private static final String VOICE_CRED = "voice-cred";
    // AUDIT-P1-13：黑名单按 jti 粒度
    private static final String ACCESS_JTI = "jti-access";
    private static final String REFRESH_JTI = "jti-refresh";
    private static final String VOICE_JTI = "jti-voice";

    @BeforeEach
    void setUp() {
        authUserService = mock(AuthUserService.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwtTokenProvider = mock(JwtTokenProvider.class);
        trialAuthStrategy = mock(TrialAuthStrategy.class);
        trialAuthService = mock(TrialAuthService.class);
        auditLogService = mock(AuditLogService.class);
        lockoutService = mock(LoginLockoutService.class);
        passwordPolicyService = mock(PasswordPolicyService.class);
        guardianConsentService = mock(GuardianConsentService.class);
        tokenBlacklistService = mock(TokenBlacklistService.class);
        businessAuthProvider = mock(com.mindsafe.api.security.BusinessAuthProvider.class);
        tenantAccessGuard = mock(TenantAccessGuard.class);
        loginOrchestrator = mock(LoginOrchestrator.class);

        controller = new AuthController(jwtTokenProvider,
                businessAuthProvider,
                trialAuthStrategy, trialAuthService, auditLogService, lockoutService,
                guardianConsentService, tokenBlacklistService,
                authUserService, loginOrchestrator);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    private LoginOrchestrator.LoginSession loginSession() {
        return new LoginOrchestrator.LoginSession(
                ACCESS_TOKEN, REFRESH_TOKEN, userId, "小星", "student", "GRADE_6", "CLASS_1", false);
    }

    private User activeStudent() {
        User u = new User();
        u.setUserId(userId);
        u.setTenantId(tenantId);
        u.setPseudonym("小星");
        u.setUserType("student");
        u.setGradeCode("GRADE_6");
        u.setClassCode("CLASS_1");
        u.setStatus("active");
        u.setPasswordHash("hash");
        u.setMustChangePassword(false);
        u.setPasswordChangedAt(Instant.now());
        return u;
    }

    private Authentication auth() {
        Authentication a = mock(Authentication.class);
        when(a.getPrincipal()).thenReturn(userId);
        when(a.getDetails()).thenReturn(new TenantContext(tenantId, userId, "student"));
        return a;
    }

    private void mockTokenIssuance() {
        when(businessAuthProvider.issueAccessToken(userId, "student", tenantId)).thenReturn(ACCESS_TOKEN);
        when(businessAuthProvider.issueRefreshToken(userId, "student", tenantId)).thenReturn(REFRESH_TOKEN);
    }

    // ===== login（S-001：编排下沉 LoginOrchestrator，链细节见 LoginOrchestratorTest） =====

    @Test
    @DisplayName("login 委托编排：LoginSession → LoginResponse 组装")
    void login_delegatesToOrchestrator() {
        LoginOrchestrator.LoginSession session = new LoginOrchestrator.LoginSession(
                ACCESS_TOKEN, REFRESH_TOKEN, userId, "小星", "student", "GRADE_6", "CLASS_1", false);
        when(loginOrchestrator.loginWithPassword("小星", "pwd12345")).thenReturn(session);

        ApiResponse<LoginResponse> resp = controller.login(new LoginRequest("小星", "pwd12345"));

        assertThat(resp.code()).isEqualTo(0);
        LoginResponse data = resp.data();
        assertEquals(ACCESS_TOKEN, data.token());
        assertEquals(REFRESH_TOKEN, data.refreshToken());
        assertEquals(userId, data.userId());
        assertEquals("小星", data.displayName());
        assertEquals("student", data.userType());
        assertEquals("GRADE_6", data.gradeCode());
        assertEquals("CLASS_1", data.classCode());
        assertThat(data.mustChangePassword()).isFalse();
    }

    @Test
    @DisplayName("login 编排异常（锁定/重名/冻结/门禁/密码）→ 透传")
    void login_orchestratorExceptionPropagates() {
        doThrow(new BizException(ErrorCode.RATE_LIMITED, "登录失败次数过多"))
                .when(loginOrchestrator).loginWithPassword("小星", "pwd12345");

        assertThatThrownBy(() -> controller.login(new LoginRequest("小星", "pwd12345")))
                .isExactlyInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.RATE_LIMITED.code());
    }

    // ===== trialRegister =====

    @Test
    @DisplayName("trialRegister 成功：age>=14 → guardianConsentPending=false")
    void trialRegister_adult() {
        AuthenticatedUser au = new AuthenticatedUser(userId, "trial_student", tenantId, "小星", false);
        when(trialAuthStrategy.authenticate(any())).thenReturn(au);
        User fullUser = activeStudent();
        fullUser.setFamilyCode("FAM-001");
        when(authUserService.findByIdAsSystem(userId)).thenReturn(fullUser);
        when(businessAuthProvider.issueAccessToken(userId, "trial_student", tenantId)).thenReturn(ACCESS_TOKEN);
        when(businessAuthProvider.issueRefreshToken(userId, "trial_student", tenantId)).thenReturn(REFRESH_TOKEN);

        TrialRegisterRequest req = new TrialRegisterRequest("CODE123", "小星", 15, null, null, "v1", null, null);
        ApiResponse<TrialRegisterResponse> resp = controller.trialRegister(req);

        assertThat(resp.code()).isEqualTo(0);
        assertEquals("FAM-001", resp.data().familyCode());
        assertThat(resp.data().guardianConsentPending()).isFalse();
        assertEquals(ACCESS_TOKEN, resp.data().token());
    }

    @Test
    @DisplayName("trialRegister age<14 且无同意记录 → guardianConsentPending=true（引导 SMS 闭环）")
    void trialRegister_minorPendingConsent() {
        AuthenticatedUser au = new AuthenticatedUser(userId, "trial_student", tenantId, "小星", false);
        when(trialAuthStrategy.authenticate(any())).thenReturn(au);
        when(authUserService.findByIdAsSystem(userId)).thenReturn(activeStudent());
        when(guardianConsentService.hasGuardianConsent(tenantId, userId)).thenReturn(false);
        when(businessAuthProvider.issueAccessToken(userId, "trial_student", tenantId)).thenReturn(ACCESS_TOKEN);
        when(businessAuthProvider.issueRefreshToken(userId, "trial_student", tenantId)).thenReturn(REFRESH_TOKEN);

        TrialRegisterRequest req = new TrialRegisterRequest("CODE123", "小星", 10, null, null, "v1", "13800000001", null);
        ApiResponse<TrialRegisterResponse> resp = controller.trialRegister(req);

        assertThat(resp.data().guardianConsentPending()).isTrue();
    }

    @Test
    @DisplayName("trialRegister age<14 已有同意记录 → guardianConsentPending=false")
    void trialRegister_minorWithConsent() {
        AuthenticatedUser au = new AuthenticatedUser(userId, "trial_student", tenantId, "小星", false);
        when(trialAuthStrategy.authenticate(any())).thenReturn(au);
        when(authUserService.findByIdAsSystem(userId)).thenReturn(activeStudent());
        when(guardianConsentService.hasGuardianConsent(tenantId, userId)).thenReturn(true);
        when(businessAuthProvider.issueAccessToken(userId, "trial_student", tenantId)).thenReturn(ACCESS_TOKEN);
        when(businessAuthProvider.issueRefreshToken(userId, "trial_student", tenantId)).thenReturn(REFRESH_TOKEN);

        TrialRegisterRequest req = new TrialRegisterRequest("CODE123", "小星", 10, null, null, "v1", "13800000001", null);
        ApiResponse<TrialRegisterResponse> resp = controller.trialRegister(req);

        assertThat(resp.data().guardianConsentPending()).isFalse();
    }

    // ===== changePassword / setPin / issueVoiceCredential =====

    @Test
    @DisplayName("changePassword 无认证上下文 → UNAUTHORIZED")
    void changePassword_noAuth() {
        assertThatThrownBy(() -> controller.changePassword(
                new ChangePasswordRequest("old", "newpass123"), null))
                .isExactlyInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.UNAUTHORIZED.code());
        verify(trialAuthService, never()).changePassword(any(), any(), any());
    }

    @Test
    @DisplayName("changePassword 认证有效 → 调用服务层")
    void changePassword_success() {
        controller.changePassword(new ChangePasswordRequest("old", "newpass123"), auth());

        verify(trialAuthService).changePassword(userId, "old", "newpass123");
    }

    @Test
    @DisplayName("setPin 无认证上下文 → UNAUTHORIZED")
    void setPin_noAuth() {
        assertThatThrownBy(() -> controller.setPin(new SetPinRequest("123456"), null))
                .isExactlyInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.UNAUTHORIZED.code());
        verify(trialAuthService, never()).setPin(any(), any());
    }

    @Test
    @DisplayName("setPin 认证有效 → 调用服务层")
    void setPin_success() {
        controller.setPin(new SetPinRequest("123456"), auth());

        verify(trialAuthService).setPin(userId, "123456");
    }

    @Test
    @DisplayName("issueVoiceCredential 无认证 → UNAUTHORIZED")
    void issueVoiceCredential_noAuth() {
        assertThatThrownBy(() -> controller.issueVoiceCredential(null))
                .isExactlyInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.UNAUTHORIZED.code());
    }

    @Test
    @DisplayName("issueVoiceCredential 成功 → 签发声纹凭证 + 审计")
    void issueVoiceCredential_success() {
        when(jwtTokenProvider.generateVoiceCredential(userId, "student", tenantId)).thenReturn(VOICE_CRED);

        ApiResponse<Map<String, String>> resp = controller.issueVoiceCredential(auth());

        assertThat(resp.code()).isEqualTo(0);
        assertEquals(VOICE_CRED, resp.data().get("voiceCredential"));
        verify(auditLogService).log(tenantId, userId, "VOICE_CREDENTIAL_ISSUE", "user", userId, null);
    }

    // ===== pinLogin =====

    @Test
    @DisplayName("pinLogin 成功 → 双 token + PIN_LOGIN 审计")
    void pinLogin_success() {
        User user = activeStudent();
        when(trialAuthService.loginWithPin("小星", "1234")).thenReturn(user);
        when(loginOrchestrator.issueLoginSession(user, "PIN_LOGIN")).thenReturn(loginSession());

        ApiResponse<LoginResponse> resp = controller.pinLogin(new PinLoginRequest("小星", "1234"));

        assertThat(resp.code()).isEqualTo(0);
        assertEquals(ACCESS_TOKEN, resp.data().token());
        verify(lockoutService).checkLockout("pin:小星");
        verify(lockoutService).clearFailures("pin:小星");
        // S-001：门禁补齐 + 审计/签发统一在 orchestrator
        verify(loginOrchestrator).guardTenantLogin(user);
        verify(loginOrchestrator).issueLoginSession(user, "PIN_LOGIN");
    }

    @Test
    @DisplayName("pinLogin 失败（服务抛 BizException）→ recordFailure + 异常传播")
    void pinLogin_failure() {
        when(trialAuthService.loginWithPin(anyString(), anyString()))
                .thenThrow(new BizException(ErrorCode.UNAUTHORIZED, "PIN 码错误"));

        assertThatThrownBy(() -> controller.pinLogin(new PinLoginRequest("小星", "0000")))
                .isExactlyInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.UNAUTHORIZED.code());
        verify(lockoutService).recordFailure("pin:小星");
        verify(lockoutService, never()).clearFailures(anyString());
    }

    // ===== voiceLogin =====

    @Test
    @DisplayName("voiceLogin 凭证无效（签名/过期）→ UNAUTHORIZED")
    void voiceLogin_invalidCredential() {
        when(jwtTokenProvider.validateToken(VOICE_CRED)).thenReturn(false);

        assertThatThrownBy(() -> controller.voiceLogin(new VoiceLoginRequest(VOICE_CRED)))
                .isExactlyInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.UNAUTHORIZED.code());
    }

    @Test
    @DisplayName("voiceLogin 非声纹凭证（access token 冒充）→ UNAUTHORIZED")
    void voiceLogin_notVoiceCredential() {
        when(jwtTokenProvider.validateToken(VOICE_CRED)).thenReturn(true);
        when(jwtTokenProvider.isVoiceCredential(VOICE_CRED)).thenReturn(false);

        assertThatThrownBy(() -> controller.voiceLogin(new VoiceLoginRequest(VOICE_CRED)))
                .isExactlyInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.UNAUTHORIZED.code());
    }

    @Test
    @DisplayName("voiceLogin 凭证已拉黑 → UNAUTHORIZED")
    void voiceLogin_blacklisted() {
        when(jwtTokenProvider.validateToken(VOICE_CRED)).thenReturn(true);
        when(jwtTokenProvider.isVoiceCredential(VOICE_CRED)).thenReturn(true);
        when(jwtTokenProvider.getTokenId(VOICE_CRED)).thenReturn(VOICE_JTI);
        when(tokenBlacklistService.isBlacklisted(VOICE_JTI)).thenReturn(true);

        assertThatThrownBy(() -> controller.voiceLogin(new VoiceLoginRequest(VOICE_CRED)))
                .isExactlyInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.UNAUTHORIZED.code());
    }

    @Test
    @DisplayName("voiceLogin 用户不存在或非 active → UNAUTHORIZED")
    void voiceLogin_userUnavailable() {
        when(jwtTokenProvider.validateToken(VOICE_CRED)).thenReturn(true);
        when(jwtTokenProvider.isVoiceCredential(VOICE_CRED)).thenReturn(true);
        when(jwtTokenProvider.getTokenId(VOICE_CRED)).thenReturn(VOICE_JTI);
        when(tokenBlacklistService.isBlacklisted(VOICE_JTI)).thenReturn(false);
        when(jwtTokenProvider.getUserId(VOICE_CRED)).thenReturn(userId);
        when(authUserService.findByIdAsSystem(userId)).thenReturn(null);

        assertThatThrownBy(() -> controller.voiceLogin(new VoiceLoginRequest(VOICE_CRED)))
                .isExactlyInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.UNAUTHORIZED.code());
    }

    @Test
    @DisplayName("voiceLogin 成功 → 双 token + VOICE_LOGIN 审计 + 更新 lastLoginAt")
    void voiceLogin_success() {
        User user = activeStudent();
        when(jwtTokenProvider.validateToken(VOICE_CRED)).thenReturn(true);
        when(jwtTokenProvider.isVoiceCredential(VOICE_CRED)).thenReturn(true);
        when(jwtTokenProvider.getTokenId(VOICE_CRED)).thenReturn(VOICE_JTI);
        when(tokenBlacklistService.isBlacklisted(VOICE_JTI)).thenReturn(false);
        when(jwtTokenProvider.getUserId(VOICE_CRED)).thenReturn(userId);
        when(authUserService.findByIdAsSystem(userId)).thenReturn(user);
        when(loginOrchestrator.issueLoginSession(user, "VOICE_LOGIN")).thenReturn(loginSession());

        ApiResponse<LoginResponse> resp = controller.voiceLogin(new VoiceLoginRequest(VOICE_CRED));

        assertThat(resp.code()).isEqualTo(0);
        assertEquals(ACCESS_TOKEN, resp.data().token());
        verify(authUserService).touchLastLogin(userId);
        // S-001：门禁补齐 + 审计/签发统一在 orchestrator
        verify(loginOrchestrator).guardTenantLogin(user);
        verify(loginOrchestrator).issueLoginSession(user, "VOICE_LOGIN");
    }

    // ===== me =====

    @Test
    @DisplayName("me 无认证 → UNAUTHORIZED")
    void me_noAuth() {
        assertThatThrownBy(() -> controller.me(null))
                .isExactlyInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.UNAUTHORIZED.code());
    }

    @Test
    @DisplayName("me 用户不存在 → RESOURCE_NOT_FOUND")
    void me_userNotFound() {
        when(authUserService.findById(userId)).thenReturn(null);

        assertThatThrownBy(() -> controller.me(auth()))
                .isExactlyInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND.code());
    }

    @Test
    @DisplayName("me 成功 → 返回用户信息")
    void me_success() {
        User user = activeStudent();
        user.setFamilyCode("FAM-001");
        user.setMustChangePassword(true);
        when(authUserService.findById(userId)).thenReturn(user);

        ApiResponse<Map<String, Object>> resp = controller.me(auth());

        assertThat(resp.code()).isEqualTo(0);
        assertEquals(userId, resp.data().get("userId"));
        assertEquals("小星", resp.data().get("displayName"));
        assertEquals(tenantId, resp.data().get("tenantId"));
        assertEquals("FAM-001", resp.data().get("familyCode"));
        assertEquals(true, resp.data().get("mustChangePassword"));
    }

    // ===== refresh =====

    @Test
    @DisplayName("refresh 无效 token → UNAUTHORIZED")
    void refresh_invalid() {
        when(jwtTokenProvider.validateToken(REFRESH_TOKEN)).thenReturn(false);

        assertThatThrownBy(() -> controller.refresh(new RefreshRequest(REFRESH_TOKEN)))
                .isExactlyInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.UNAUTHORIZED.code());
    }

    @Test
    @DisplayName("refresh 非 refresh token（access 冒充）→ UNAUTHORIZED")
    void refresh_notRefreshToken() {
        when(jwtTokenProvider.validateToken(REFRESH_TOKEN)).thenReturn(true);
        when(jwtTokenProvider.isRefreshToken(REFRESH_TOKEN)).thenReturn(false);

        assertThatThrownBy(() -> controller.refresh(new RefreshRequest(REFRESH_TOKEN)))
                .isExactlyInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.UNAUTHORIZED.code());
    }

    @Test
    @DisplayName("refresh 已拉黑 → UNAUTHORIZED（防重放）")
    void refresh_blacklisted() {
        when(jwtTokenProvider.validateToken(REFRESH_TOKEN)).thenReturn(true);
        when(jwtTokenProvider.isRefreshToken(REFRESH_TOKEN)).thenReturn(true);
        when(jwtTokenProvider.getTokenId(REFRESH_TOKEN)).thenReturn(REFRESH_JTI);
        when(tokenBlacklistService.isBlacklisted(REFRESH_JTI)).thenReturn(true);

        assertThatThrownBy(() -> controller.refresh(new RefreshRequest(REFRESH_TOKEN)))
                .isExactlyInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.UNAUTHORIZED.code());
        verify(tokenBlacklistService, never()).blacklist(anyString(), any(Long.class));
    }

    @Test
    @DisplayName("refresh 成功 → 新双 token + 旧 refresh 拉黑")
    void refresh_success() {
        when(jwtTokenProvider.validateToken(REFRESH_TOKEN)).thenReturn(true);
        when(jwtTokenProvider.isRefreshToken(REFRESH_TOKEN)).thenReturn(true);
        when(jwtTokenProvider.getTokenId(REFRESH_TOKEN)).thenReturn(REFRESH_JTI);
        when(tokenBlacklistService.isBlacklisted(REFRESH_JTI)).thenReturn(false);
        when(jwtTokenProvider.getUserId(REFRESH_TOKEN)).thenReturn(userId);
        when(jwtTokenProvider.getUserType(REFRESH_TOKEN)).thenReturn("student");
        when(jwtTokenProvider.getTenantId(REFRESH_TOKEN)).thenReturn(tenantId);
        when(jwtTokenProvider.getRemainingMs(REFRESH_TOKEN)).thenReturn(3600000L);
        when(businessAuthProvider.issueAccessToken(userId, "student", tenantId)).thenReturn("new-access");
        when(businessAuthProvider.issueRefreshToken(userId, "student", tenantId)).thenReturn("new-refresh");

        ApiResponse<Map<String, String>> resp = controller.refresh(new RefreshRequest(REFRESH_TOKEN));

        assertThat(resp.code()).isEqualTo(0);
        assertEquals("new-access", resp.data().get("token"));
        assertEquals("new-refresh", resp.data().get("refreshToken"));
        verify(tokenBlacklistService).blacklist(REFRESH_JTI, 3600000L);
    }

    // ===== logout =====

    @Test
    @DisplayName("logout 成功：access + refresh 双拉黑 + 审计")
    void logout_success() {
        when(jwtTokenProvider.getTokenId(ACCESS_TOKEN)).thenReturn(ACCESS_JTI);
        when(jwtTokenProvider.getRemainingMs(ACCESS_TOKEN)).thenReturn(7200000L);
        when(jwtTokenProvider.validateToken(REFRESH_TOKEN)).thenReturn(true);
        when(jwtTokenProvider.getTokenId(REFRESH_TOKEN)).thenReturn(REFRESH_JTI);
        when(jwtTokenProvider.getRemainingMs(REFRESH_TOKEN)).thenReturn(604800000L);

        ApiResponse<Void> resp = controller.logout("Bearer " + ACCESS_TOKEN,
                new LogoutRequest(REFRESH_TOKEN), auth());

        assertThat(resp.code()).isEqualTo(0);
        verify(tokenBlacklistService).blacklist(ACCESS_JTI, 7200000L);
        verify(tokenBlacklistService).blacklist(REFRESH_JTI, 604800000L);
        verify(auditLogService).log(tenantId, userId, "LOGOUT", "user", userId, null);
    }

    @Test
    @DisplayName("logout 未传 refresh → 仅拉黑 access")
    void logout_noRefresh() {
        when(jwtTokenProvider.getTokenId(ACCESS_TOKEN)).thenReturn(ACCESS_JTI);
        when(jwtTokenProvider.getRemainingMs(ACCESS_TOKEN)).thenReturn(7200000L);

        controller.logout("Bearer " + ACCESS_TOKEN, null, null);

        verify(tokenBlacklistService).blacklist(ACCESS_JTI, 7200000L);
        verify(tokenBlacklistService, never()).blacklist(REFRESH_JTI, 604800000L);
    }

    @Test
    @DisplayName("logout refresh 已过期（validateToken false）→ 跳过拉黑不报错")
    void logout_invalidRefresh() {
        when(jwtTokenProvider.getTokenId(ACCESS_TOKEN)).thenReturn(ACCESS_JTI);
        when(jwtTokenProvider.getRemainingMs(ACCESS_TOKEN)).thenReturn(7200000L);
        when(jwtTokenProvider.validateToken(REFRESH_TOKEN)).thenReturn(false);

        ApiResponse<Void> resp = controller.logout("Bearer " + ACCESS_TOKEN,
                new LogoutRequest(REFRESH_TOKEN), null);

        assertThat(resp.code()).isEqualTo(0);
        verify(tokenBlacklistService).blacklist(ACCESS_JTI, 7200000L);
        verify(tokenBlacklistService, never()).blacklist(REFRESH_JTI, 604800000L);
    }

    // ===== guardian-consent 闭环 =====

    @Test
    @DisplayName("requestGuardianConsent → 调用服务发送验证码")
    void requestGuardianConsent_success() {
        ApiResponse<Map<String, String>> resp = controller.requestGuardianConsent(
                new GuardianConsentRequest("13800000001"), auth());

        assertThat(resp.code()).isEqualTo(0);
        assertEquals("sent", resp.data().get("status"));
        verify(guardianConsentService).requestConsent(tenantId, userId, "13800000001");
    }

    @Test
    @DisplayName("confirmGuardianConsent → 校验验证码并写入同意记录")
    void confirmGuardianConsent_success() {
        ApiResponse<Map<String, Object>> resp = controller.confirmGuardianConsent(
                new GuardianConsentConfirmRequest("13800000001", "123456"), auth());

        assertThat(resp.code()).isEqualTo(0);
        assertEquals("confirmed", resp.data().get("status"));
        verify(guardianConsentService).confirmConsent(tenantId, userId, "13800000001", "123456");
    }

    // ===== 租户上下文绑定（login 审计路径下沉 Service） =====

    @Test
    @DisplayName("login 委托编排：请求结束后无租户上下文遗留（绑定/清除在 orchestrator 内）")
    void login_bindsTenantContextForAudit() {
        when(loginOrchestrator.loginWithPassword("小星", "pwd12345")).thenReturn(loginSession());
    
        controller.login(new LoginRequest("小星", "pwd12345"));
    
        assertNull(TenantContextHolder.get(), "请求结束后租户上下文必须清除");
    }
}
