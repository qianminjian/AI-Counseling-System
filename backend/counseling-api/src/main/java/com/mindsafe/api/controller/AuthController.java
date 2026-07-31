package com.mindsafe.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.api.auth.AuthenticatedUser;
import com.mindsafe.api.auth.TrialAuthStrategy;
import com.mindsafe.api.auth.TrialRegisterRequest;
import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.api.security.JwtTokenProvider;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.UserMapper;
import com.mindsafe.service.auth.TrialAuthService;
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

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 认证 API
 * <ul>
 *   <li>POST /login — 学号/昵称 + 密码登录（学生/教师/管理员通用）</li>
 *   <li>POST /trial/register — 试用注册（邀请码 + 昵称 + 年龄 + 同意）</li>
 *   <li>POST /change-password — 修改密码（首次设密 / 常规改密）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final TrialAuthStrategy trialAuthStrategy;
    private final TrialAuthService trialAuthService;
    private final AuditLogService auditLogService;
    private final LoginLockoutService lockoutService;
    private final PasswordPolicyService passwordPolicyService;
    private final GuardianConsentService guardianConsentService;
    private final TokenBlacklistService tokenBlacklistService;

    public AuthController(UserMapper userMapper,
                          PasswordEncoder passwordEncoder,
                          JwtTokenProvider jwtTokenProvider,
                          TrialAuthStrategy trialAuthStrategy,
                          TrialAuthService trialAuthService,
                          AuditLogService auditLogService,
                          LoginLockoutService lockoutService,
                          PasswordPolicyService passwordPolicyService,
                          GuardianConsentService guardianConsentService,
                          TokenBlacklistService tokenBlacklistService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.trialAuthStrategy = trialAuthStrategy;
        this.trialAuthService = trialAuthService;
        this.auditLogService = auditLogService;
        this.lockoutService = lockoutService;
        this.passwordPolicyService = passwordPolicyService;
        this.guardianConsentService = guardianConsentService;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    /**
     * 通用登录（pseudonym + 密码）
     */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        // 登录失败锁定检查
        lockoutService.checkLockout(request.username());

        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getPseudonym, request.username())
                        .eq(User::getStatus, "active")
                        .last("LIMIT 1")
        );

        if (user == null || user.getPasswordHash() == null
                || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            lockoutService.recordFailure(request.username());
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }

        // 登录成功，清除失败计数
        lockoutService.clearFailures(request.username());

        // 更新最后登录时间
        User update = new User();
        update.setUserId(user.getUserId());
        update.setLastLoginAt(Instant.now());
        userMapper.updateById(update);

        String token = jwtTokenProvider.generateToken(
                user.getUserId(), user.getUserType(), user.getTenantId());
        String refreshToken = jwtTokenProvider.generateRefreshToken(
                user.getUserId(), user.getUserType(), user.getTenantId());

        // 审计：登录成功
        auditLogService.log(user.getTenantId(), user.getUserId(), "LOGIN", "user", user.getUserId(), null);

        boolean mustChange = Boolean.TRUE.equals(user.getMustChangePassword())
                || passwordPolicyService.isExpired(user.getPasswordChangedAt());

        return ApiResponse.ok(new LoginResponse(
                token,
                refreshToken,
                user.getUserId(),
                user.getPseudonym(),
                user.getUserType(),
                user.getGradeCode(),
                user.getClassCode(),
                mustChange
        ));
    }

    /**
     * 试用注册（邀请码 + 昵称 + 年龄 + 告知同意）
     */
    @PostMapping("/trial/register")
    public ApiResponse<TrialRegisterResponse> trialRegister(
            @Valid @RequestBody TrialRegisterRequest request) {
        AuthenticatedUser authUser = trialAuthStrategy.authenticate(request);

        // 查询完整用户信息（含 familyCode）
        User fullUser = userMapper.selectById(authUser.userId());

        String token = jwtTokenProvider.generateToken(
                authUser.userId(), authUser.userType(), authUser.tenantId());
        String refreshToken = jwtTokenProvider.generateRefreshToken(
                authUser.userId(), authUser.userType(), authUser.tenantId());

        return ApiResponse.ok(new TrialRegisterResponse(
                token,
                refreshToken,
                authUser.userId(),
                authUser.tenantId(),
                authUser.userType(),
                authUser.pseudonym(),
                fullUser != null ? fullUser.getFamilyCode() : null
        ));
    }

    /**
     * 修改密码（首次设密 / 常规改密，需已登录）
     */
    @PostMapping("/change-password")
    public ApiResponse<Void> changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            Authentication authentication) {
        if (authentication == null || !(authentication.getDetails() instanceof TenantContext ctx)) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
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
        if (authentication == null || !(authentication.getDetails() instanceof TenantContext ctx)) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
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
            User user = trialAuthService.loginWithPin(request.pseudonym(), request.pin());
            lockoutService.clearFailures(lockKey);
            String token = jwtTokenProvider.generateToken(
                    user.getUserId(), user.getUserType(), user.getTenantId());
            String refreshToken = jwtTokenProvider.generateRefreshToken(
                    user.getUserId(), user.getUserType(), user.getTenantId());
            auditLogService.log(user.getTenantId(), user.getUserId(), "PIN_LOGIN", "user", user.getUserId(), null);
            return ApiResponse.ok(new LoginResponse(
                    token, refreshToken, user.getUserId(), user.getPseudonym(),
                    user.getUserType(), user.getGradeCode(), user.getClassCode(), false
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
        if (authentication == null || !(authentication.getDetails() instanceof TenantContext ctx)) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
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
        if (vc == null || !jwtTokenProvider.validateToken(vc) || !jwtTokenProvider.isVoiceCredential(vc)) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "设备凭证无效或已过期，请重新录入声纹");
        }
        if (tokenBlacklistService.isBlacklisted(vc)) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "设备凭证已失效，请重新录入声纹");
        }

        User user = userMapper.selectById(jwtTokenProvider.getUserId(vc));
        if (user == null || !"active".equals(user.getStatus())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "账号不可用，请联系老师");
        }

        // 更新最后登录时间
        User update = new User();
        update.setUserId(user.getUserId());
        update.setLastLoginAt(Instant.now());
        userMapper.updateById(update);

        String token = jwtTokenProvider.generateToken(
                user.getUserId(), user.getUserType(), user.getTenantId());
        String refreshToken = jwtTokenProvider.generateRefreshToken(
                user.getUserId(), user.getUserType(), user.getTenantId());

        auditLogService.log(user.getTenantId(), user.getUserId(), "VOICE_LOGIN", "user", user.getUserId(), null);

        return ApiResponse.ok(new LoginResponse(
                token, refreshToken, user.getUserId(), user.getPseudonym(),
                user.getUserType(), user.getGradeCode(), user.getClassCode(), false
        ));
    }

    /**
     * 获取当前用户信息（前端刷新页面恢复状态）
     */
    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me(Authentication authentication) {
        if (authentication == null || !(authentication.getDetails() instanceof TenantContext ctx)) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        User user = userMapper.selectById(ctx.userId());
        if (user == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "用户不存在");
        }
        Map<String, Object> info = new java.util.LinkedHashMap<>();
        info.put("userId", user.getUserId());
        info.put("displayName", user.getPseudonym());
        info.put("userType", user.getUserType());
        info.put("tenantId", user.getTenantId());
        info.put("schoolId", user.getSchoolId());
        info.put("gradeCode", user.getGradeCode());
        info.put("classCode", user.getClassCode());
        info.put("mustChangePassword", Boolean.TRUE.equals(user.getMustChangePassword()));
        info.put("familyCode", user.getFamilyCode());
        return ApiResponse.ok(info);
    }

    /**
     * 刷新 Token（用 refresh token 换取新的 access + refresh）
     */
    @PostMapping("/refresh")
    public ApiResponse<Map<String, String>> refresh(@RequestBody RefreshRequest request) {
        String rt = request.refreshToken();
        if (rt == null || !jwtTokenProvider.validateToken(rt) || !jwtTokenProvider.isRefreshToken(rt)) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "刷新令牌无效或已过期，请重新登录");
        }
        if (tokenBlacklistService.isBlacklisted(rt)) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "令牌已失效，请重新登录");
        }

        UUID userId = jwtTokenProvider.getUserId(rt);
        String userType = jwtTokenProvider.getUserType(rt);
        UUID tenantId = jwtTokenProvider.getTenantId(rt);

        // 签发新双 token
        String newAccess = jwtTokenProvider.generateToken(userId, userType, tenantId);
        String newRefresh = jwtTokenProvider.generateRefreshToken(userId, userType, tenantId);

        // 旧 refresh token 拉黑（防重放）
        tokenBlacklistService.blacklist(rt, jwtTokenProvider.getRemainingMs(rt));

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
        // 拉黑 access token
        String accessToken = authHeader.replace("Bearer ", "");
        tokenBlacklistService.blacklist(accessToken, jwtTokenProvider.getRemainingMs(accessToken));

        // 拉黑 refresh token（如果前端传了）
        if (request != null && request.refreshToken() != null && !request.refreshToken().isBlank()) {
            String rt = request.refreshToken();
            if (jwtTokenProvider.validateToken(rt)) {
                tokenBlacklistService.blacklist(rt, jwtTokenProvider.getRemainingMs(rt));
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
            String familyCode
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
        TenantContext ctx = (TenantContext) auth.getDetails();
        UUID studentUserId = (UUID) auth.getPrincipal();
        guardianConsentService.requestConsent(ctx.tenantId(), studentUserId, request.guardianPhone());
        return ApiResponse.ok(Map.of("status", "sent", "message", "验证码已发送到监护人手机"));
    }

    /**
     * 确认监护人同意（验证码校验 + 写入同意记录）
     */
    @PostMapping("/guardian-consent/confirm")
    public ApiResponse<Map<String, Object>> confirmGuardianConsent(
            @RequestBody GuardianConsentConfirmRequest request, Authentication auth) {
        TenantContext ctx = (TenantContext) auth.getDetails();
        UUID studentUserId = (UUID) auth.getPrincipal();
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
