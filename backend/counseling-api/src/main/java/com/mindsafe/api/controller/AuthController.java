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
import com.mindsafe.service.audit.AuditLogService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
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

    public AuthController(UserMapper userMapper,
                          PasswordEncoder passwordEncoder,
                          JwtTokenProvider jwtTokenProvider,
                          TrialAuthStrategy trialAuthStrategy,
                          TrialAuthService trialAuthService,
                          AuditLogService auditLogService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.trialAuthStrategy = trialAuthStrategy;
        this.trialAuthService = trialAuthService;
        this.auditLogService = auditLogService;
    }

    /**
     * 通用登录（pseudonym + 密码）
     */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getPseudonym, request.username())
                        .eq(User::getStatus, "active")
                        .last("LIMIT 1")
        );

        if (user == null || user.getPasswordHash() == null
                || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }

        // 更新最后登录时间
        User update = new User();
        update.setUserId(user.getUserId());
        update.setLastLoginAt(Instant.now());
        userMapper.updateById(update);

        String token = jwtTokenProvider.generateToken(
                user.getUserId(), user.getUserType(), user.getTenantId());

        // 审计：登录成功
        auditLogService.log(user.getTenantId(), user.getUserId(), "LOGIN", "user", user.getUserId(), null);

        boolean mustChange = Boolean.TRUE.equals(user.getMustChangePassword());

        return ApiResponse.ok(new LoginResponse(
                token,
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

        String token = jwtTokenProvider.generateToken(
                authUser.userId(), authUser.userType(), authUser.tenantId());

        return ApiResponse.ok(new TrialRegisterResponse(
                token,
                authUser.userId(),
                authUser.tenantId(),
                authUser.userType(),
                authUser.pseudonym()
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

    // ===== Request / Response Records =====

    public record LoginRequest(
            @NotBlank(message = "用户名不能为空") String username,
            @NotBlank(message = "密码不能为空") String password
    ) {}

    public record LoginResponse(
            String token,
            UUID userId,
            String displayName,
            String userType,
            String gradeCode,
            String classCode,
            boolean mustChangePassword
    ) {}

    public record TrialRegisterResponse(
            String token,
            UUID userId,
            UUID tenantId,
            String userType,
            String pseudonym
    ) {}

    public record ChangePasswordRequest(
            @NotBlank(message = "旧密码不能为空") String oldPassword,
            @NotBlank(message = "新密码不能为空")
            @Size(min = 8, max = 64, message = "新密码长度 8-64 位") String newPassword
    ) {}
}
