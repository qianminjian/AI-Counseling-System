package com.mindsafe.api.controller;

import com.mindsafe.api.auth.AuthenticatedUser;
import com.mindsafe.api.auth.LoginCandidate;
import com.mindsafe.api.auth.LoginOrchestrator;
import com.mindsafe.api.auth.TrialAuthStrategy;
import com.mindsafe.api.auth.TrialRegisterRequest;
import com.mindsafe.api.auth.UserSnapshot;
import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.api.security.SecuritySupport;
import com.mindsafe.api.security.TokenType;
import com.mindsafe.api.security.BusinessAuthProvider;
import com.mindsafe.api.security.JwtTokenProvider;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.common.tenant.TenantContextHolder;
import com.mindsafe.domain.entity.User;
import com.mindsafe.service.auth.AuthUserService;
import com.mindsafe.service.auth.TrialAuthService;
import com.mindsafe.service.auth.TenantAccessGuard;
import com.mindsafe.service.auth.LoginLockoutService;
import com.mindsafe.service.auth.PasswordPolicyService;
import com.mindsafe.service.auth.TokenBlacklistService;
import com.mindsafe.service.audit.AuditLogService;
import com.mindsafe.service.consent.GuardianConsentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * 认证 API（F22，doing/97：承载 9 类端点，分区注释如下——监护人与认证职责混杂暂不分拆，
 * 新增端点时按分区归位，待监护人闭环独立部署需求出现时再拆 Controller）
 * <ul>
 *   <li>登录区：POST /login（学号/昵称 + 密码）、POST /refresh、POST /logout</li>
 *   <li>试用注册区：POST /trial/register（邀请码 + 昵称 + 年龄 + 同意）</li>
 *   <li>密码区：POST /change-password（首次设密 / 常规改密）</li>
 *   <li>PIN 区：PIN 设置/校验</li>
 *   <li>声纹区：声纹登录凭证</li>
 *   <li>用户信息区：GET /me</li>
 *   <li>监护人同意区（AUTH-023）：请求/确认</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AuthController.class);

    private final JwtTokenProvider jwtTokenProvider;
    private final BusinessAuthProvider businessAuthProvider;
    private final TrialAuthStrategy trialAuthStrategy;
    private final TrialAuthService trialAuthService;
    private final AuditLogService auditLogService;
    private final LoginLockoutService lockoutService;
    private final GuardianConsentService guardianConsentService;
    private final TokenBlacklistService tokenBlacklistService;
    private final AuthUserService authUserService;
    /** S-001（doing/93）：登录编排单点（固定顺序 + 签发/门禁统一） */
    private final LoginOrchestrator loginOrchestrator;

    public AuthController(JwtTokenProvider jwtTokenProvider,
                          BusinessAuthProvider businessAuthProvider,
                          TrialAuthStrategy trialAuthStrategy,
                          TrialAuthService trialAuthService,
                          AuditLogService auditLogService,
                          LoginLockoutService lockoutService,
                          GuardianConsentService guardianConsentService,
                          TokenBlacklistService tokenBlacklistService,
                          AuthUserService authUserService,
                          LoginOrchestrator loginOrchestrator) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.businessAuthProvider = businessAuthProvider;
        this.trialAuthStrategy = trialAuthStrategy;
        this.trialAuthService = trialAuthService;
        this.auditLogService = auditLogService;
        this.lockoutService = lockoutService;
        this.guardianConsentService = guardianConsentService;
        this.tokenBlacklistService = tokenBlacklistService;
        this.authUserService = authUserService;
        this.loginOrchestrator = loginOrchestrator;
    }

    /**
     * 通用登录（pseudonym + 密码）——编排下沉 LoginOrchestrator（S-001：固定顺序单点）
     */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        LoginOrchestrator.LoginSession session = loginOrchestrator.loginWithPassword(
                request.username(), request.password());
        return ApiResponse.ok(new LoginResponse(
                session.accessToken(), session.refreshToken(), session.userId(),
                session.pseudonym(), session.userType(),
                session.gradeCode(), session.classCode(), session.mustChangePassword()
        ));
    }

    /**
     * 试用注册（邀请码 + 昵称 + 年龄 + 告知同意）
     */
    @PostMapping("/trial/register")
    public ApiResponse<TrialRegisterResponse> trialRegister(
            @Valid @RequestBody TrialRegisterRequest request) {
        AuthenticatedUser authUser = trialAuthStrategy.authenticate(request);

        // 查询完整用户信息（含 familyCode）+ 监护人同意状态——注册响应期尚无 JWT 上下文，系统作用域执行（M1-003）
        // F9：实体立即快照为 UserSnapshot（不含 passwordHash/pinHash），仅取 familyCode 字段
        UserSnapshot fullUser = UserSnapshot.from(authUserService.findByIdAsSystem(authUser.userId()));
        // age<14 且尚无同意记录 → 前端须引导 SMS 闭环（AUTH-040；试运行 auto-grant 时注册已写入，此处为 false）
        boolean guardianConsentPending = request.age() < 14
                && !TenantContextHolder.callAsSystem(
                        () -> guardianConsentService.hasGuardianConsent(authUser.tenantId(), authUser.userId()));

        String token = businessAuthProvider.issueAccessToken(
                authUser.userId(), authUser.userType(), authUser.tenantId());
        String refreshToken = businessAuthProvider.issueRefreshToken(
                authUser.userId(), authUser.userType(), authUser.tenantId());

        return ApiResponse.ok(new TrialRegisterResponse(
                token,
                refreshToken,
                authUser.userId(),
                authUser.tenantId(),
                authUser.userType(),
                authUser.pseudonym(),
                fullUser != null ? fullUser.familyCode() : null,
                guardianConsentPending
        ));
    }

    /**
     * 修改密码（首次设密 / 常规改密，需已登录）
     */
    @PostMapping("/change-password")
    public ApiResponse<Void> changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            Authentication authentication) {
        TenantContext ctx = SecuritySupport.requireContext(authentication);
        trialAuthService.changePassword(ctx.userId(), request.oldPassword(), request.newPassword());
        return ApiResponse.ok();
    }

    /**
     * 设置 PIN 码（学生注册后设置 4-6 位数字 PIN，需已登录）
     */
    @PostMapping("/set-pin")
    public ApiResponse<Void> setPin(
            @Valid @RequestBody SetPinRequest request,
            Authentication authentication) {
        TenantContext ctx = SecuritySupport.requireContext(authentication);
        trialAuthService.setPin(ctx.userId(), request.pin());
        return ApiResponse.ok();
    }

    /**
     * PIN 码登录（学生快捷登录，无需密码）
     */
    @PostMapping("/pin-login")
    public ApiResponse<LoginResponse> pinLogin(@Valid @RequestBody PinLoginRequest request) {
        String lockKey = "pin:" + request.pseudonym();
        lockoutService.checkLockout(lockKey);
        try {
            // F9：service 返回的 User 实体在边界立即快照为 LoginCandidate（含 passwordHash 供签发期判定改密）
            LoginCandidate candidate = LoginCandidate.from(trialAuthService.loginWithPin(request.pseudonym(), request.pin()));
            lockoutService.clearFailures(lockKey);
            // S-001：补齐租户门禁 + 统一签发/审计（原路径缺失门禁、手工 TenantContextHolder）
            loginOrchestrator.guardTenantLogin(candidate);
            LoginOrchestrator.LoginSession session = loginOrchestrator.issueLoginSession(candidate, "PIN_LOGIN");
            return ApiResponse.ok(new LoginResponse(
                    session.accessToken(), session.refreshToken(), session.userId(),
                    session.pseudonym(), session.userType(),
                    session.gradeCode(), session.classCode(), session.mustChangePassword()
            ));
        } catch (BizException e) {
            lockoutService.recordFailure(lockKey);
            throw e;
        }
    }

    // ===== Request / Response Records =====

    /**
     * 签发声纹设备凭证（声纹录入完成后调用，需已登录）
     * <p>
     * 凭证与声纹模板一起存学生设备本地（IndexedDB），声纹登录时凭其换取正式双 token。
     * 声纹比对在设备本地完成（design/25 §3.7 Phase 1），本端点补齐 Phase 2 后端签发链路。
     */
    @PostMapping("/voice-credential")
    public ApiResponse<Map<String, String>> issueVoiceCredential(Authentication authentication) {
        TenantContext ctx = SecuritySupport.requireContext(authentication);
        String credential = jwtTokenProvider.generateVoiceCredential(
                ctx.userId(), ctx.userType(), ctx.tenantId());
        auditLogService.log(ctx.tenantId(), ctx.userId(), "VOICE_CREDENTIAL_ISSUE", "user", ctx.userId(), null);
        return ApiResponse.ok(Map.of("voiceCredential", credential));
    }

    /**
     * 声纹登录（设备本地声纹比对通过后，用设备凭证换取正式 access + refresh token）
     */
    @PostMapping("/voice-login")
    public ApiResponse<LoginResponse> voiceLogin(@Valid @RequestBody VoiceLoginRequest request) {
        String vc = request.voiceCredential();
        // F2：单次 parse（原 validate + isVoiceCredential + getTokenId + getUserId 4 次 → 1 次）
        JwtTokenProvider.ParsedToken parsed = jwtTokenProvider.parseOrNull(vc);
        if (parsed == null || parsed.tokenType() != TokenType.VOICE_CREDENTIAL) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "设备凭证无效或已过期，请重新录入声纹");
        }
        if (tokenBlacklistService.isBlacklisted(parsed.tokenId())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "设备凭证已失效，请重新录入声纹");
        }

        // 声纹登录是 permitAll 端点，无 Authorization header → JwtAuthenticationFilter 不设置租户上下文
        // 需显式声明系统作用域，否则多租户拦截器拒绝 SQL（M1-003）
        // T4 批次B：查询下沉 AuthUserService（系统作用域在 Service 内声明）；F9：实体边界立即快照
        LoginCandidate candidate = LoginCandidate.from(authUserService.findByIdAsSystem(parsed.userId()));
        if (candidate == null || !User.STATUS_ACTIVE.equals(candidate.status())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "账号不可用，请联系老师");
        }

        // 更新最后登录时间（T4 批次B：下沉 AuthUserService）
        authUserService.touchLastLogin(candidate.userId());

        // S-001：补齐租户门禁 + 统一签发/审计（原路径缺失门禁）
        loginOrchestrator.guardTenantLogin(candidate);
        LoginOrchestrator.LoginSession session = loginOrchestrator.issueLoginSession(candidate, "VOICE_LOGIN");
        return ApiResponse.ok(new LoginResponse(
                session.accessToken(), session.refreshToken(), session.userId(),
                session.pseudonym(), session.userType(),
                session.gradeCode(), session.classCode(), session.mustChangePassword()
        ));
    }

    /**
     * 获取当前用户信息（前端刷新页面恢复状态）
     */
    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me(Authentication authentication) {
        TenantContext ctx = SecuritySupport.requireContext(authentication);
        // F9：展示快照 UserSnapshot（不含 passwordHash/pinHash），实体不再流入响应组装
        UserSnapshot user = UserSnapshot.from(authUserService.findById(ctx.userId()));
        if (user == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "用户不存在");
        }
        Map<String, Object> info = new java.util.LinkedHashMap<>();
        info.put("userId", user.userId());
        info.put("displayName", user.pseudonym());
        info.put("userType", user.userType());
        info.put("tenantId", user.tenantId());
        info.put("schoolId", user.schoolId());
        info.put("gradeCode", user.gradeCode());
        info.put("classCode", user.classCode());
        info.put("mustChangePassword", Boolean.TRUE.equals(user.mustChangePassword()));
        info.put("familyCode", user.familyCode());
        return ApiResponse.ok(info);
    }

    /**
     * 刷新 Token（用 refresh token 换取新的 access + refresh）
     */
    @PostMapping("/refresh")
    public ApiResponse<Map<String, String>> refresh(@RequestBody RefreshRequest request) {
        String rt = request.refreshToken();
        // F2：单次 parse（原 validate + isRefresh + getTokenId×2 + getUserId + getUserType
        // + getTenantId + getRemainingMs 共 8 次 parse → 2 次）
        JwtTokenProvider.ParsedToken parsed = jwtTokenProvider.parseOrNull(rt);
        if (parsed == null || parsed.tokenType() != TokenType.REFRESH) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "刷新令牌无效或已过期，请重新登录");
        }
        if (tokenBlacklistService.isBlacklisted(parsed.tokenId())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "令牌已失效，请重新登录");
        }

        UUID userId = parsed.userId();
        String userType = parsed.userType();
        UUID tenantId = parsed.tenantId();

        // 签发新双 token
        String newAccess = businessAuthProvider.issueAccessToken(userId, userType, tenantId);
        String newRefresh = businessAuthProvider.issueRefreshToken(userId, userType, tenantId);

        // 旧 refresh token 拉黑（防重放；AUDIT-P1-13 按 jti 粒度）
        tokenBlacklistService.blacklist(parsed.tokenId(), jwtTokenProvider.getRemainingMs(rt));

        return ApiResponse.ok(Map.of("token", newAccess, "refreshToken", newRefresh));
    }

    /**
     * 登出（将当前 access token + 可选 refresh token 加入黑名单）
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody(required = false) LogoutRequest request,
            Authentication authentication) {
        // 拉黑 access token（AUDIT-P1-13：按 jti 粒度；F2：parseOrNull 容错——
        // token 已过期/签名非法时跳过拉黑，登出幂等返回 200，不再 500）
        // F20（doing/97）：Bearer 前缀校验收敛——无前缀视为非法，跳过拉黑（幂等语义不变）
        String accessToken = JwtTokenProvider.extractBearerToken(authHeader);
        JwtTokenProvider.ParsedToken parsedAccess = accessToken == null ? null : jwtTokenProvider.parseOrNull(accessToken);
        if (parsedAccess != null) {
            tokenBlacklistService.blacklist(parsedAccess.tokenId(), jwtTokenProvider.getRemainingMs(accessToken));
        }

        // 拉黑 refresh token（如果前端传了）
        if (request != null && request.refreshToken() != null && !request.refreshToken().isBlank()) {
            String rt = request.refreshToken();
            JwtTokenProvider.ParsedToken parsedRefresh = jwtTokenProvider.parseOrNull(rt);
            if (parsedRefresh != null) {
                tokenBlacklistService.blacklist(parsedRefresh.tokenId(), jwtTokenProvider.getRemainingMs(rt));
            }
        }

        // 审计
        if (authentication != null && authentication.getDetails() instanceof TenantContext ctx) {
            auditLogService.log(ctx.tenantId(), ctx.userId(), "LOGOUT", "user", ctx.userId(), null);
        }

        return ApiResponse.ok();
    }

    public record LoginRequest(
            @NotBlank(message = "用户名不能为空") String username,
            @NotBlank(message = "密码不能为空") String password
    ) {}

    public record LoginResponse(
            String token,
            String refreshToken,
            UUID userId,
            String displayName,
            String userType,
            String gradeCode,
            String classCode,
            boolean mustChangePassword
    ) {}

    public record TrialRegisterResponse(
            String token,
            String refreshToken,
            UUID userId,
            UUID tenantId,
            String userType,
            String pseudonym,
            String familyCode,
            /** age<14 且尚无监护人同意记录 → 前端须走 SMS 验证码闭环（AUTH-040） */
            boolean guardianConsentPending
    ) {}

    public record ChangePasswordRequest(
            @NotBlank(message = "旧密码不能为空") String oldPassword,
            @NotBlank(message = "新密码不能为空")
            @Size(min = 8, max = 64, message = "新密码长度 8-64 位") String newPassword
    ) {}

    public record SetPinRequest(
            @NotBlank(message = "PIN 码不能为空")
            @jakarta.validation.constraints.Pattern(regexp = "\\d{4,6}", message = "PIN 码必须为 4-6 位数字")
            String pin
    ) {}

    public record PinLoginRequest(
            @NotBlank(message = "昵称不能为空") String pseudonym,
            @NotBlank(message = "PIN 码不能为空") String pin
    ) {}

    public record RefreshRequest(String refreshToken) {}

    public record VoiceLoginRequest(
            @NotBlank(message = "设备凭证不能为空") String voiceCredential
    ) {}

    public record LogoutRequest(String refreshToken) {}

    // ===== AUTH-023 监护人同意闭环 =====

    /**
     * 发起监护人同意请求（发送短信验证码到监护人手机）
     */
    @PostMapping("/guardian-consent/request")
    public ApiResponse<Map<String, String>> requestGuardianConsent(
            @RequestBody GuardianConsentRequest request, Authentication auth) {
        TenantContext ctx = SecuritySupport.requireContext(auth);
        UUID studentUserId = ctx.userId();
        guardianConsentService.requestConsent(ctx.tenantId(), studentUserId, request.guardianPhone());
        return ApiResponse.ok(Map.of("status", "sent", "message", "验证码已发送到监护人手机"));
    }

    /**
     * 确认监护人同意（验证码校验 + 写入同意记录）
     */
    @PostMapping("/guardian-consent/confirm")
    public ApiResponse<Map<String, Object>> confirmGuardianConsent(
            @RequestBody GuardianConsentConfirmRequest request, Authentication auth) {
        TenantContext ctx = SecuritySupport.requireContext(auth);
        UUID studentUserId = ctx.userId();
        guardianConsentService.confirmConsent(ctx.tenantId(), studentUserId,
                request.guardianPhone(), request.code());
        return ApiResponse.ok(Map.of("status", "confirmed", "message", "监护人同意已确认"));
    }

    public record GuardianConsentRequest(
            @NotBlank(message = "监护人手机号不能为空") String guardianPhone
    ) {}

    public record GuardianConsentConfirmRequest(
            @NotBlank(message = "监护人手机号不能为空") String guardianPhone,
            @NotBlank(message = "验证码不能为空") String code
    ) {}
}
