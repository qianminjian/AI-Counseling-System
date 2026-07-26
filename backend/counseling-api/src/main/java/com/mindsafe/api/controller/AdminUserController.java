package com.mindsafe.api.controller;

import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.UserMapper;
import com.mindsafe.service.audit.AuditLogService;
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
 * 管理端 - 用户管理 API（密码重置等）
 * <p>
 * 权限：仅 ADMIN 角色（SecurityConfig /api/v1/admin/** → hasRole("ADMIN")）
 */
@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;

    public AdminUserController(UserMapper userMapper,
                               PasswordEncoder passwordEncoder,
                               AuditLogService auditLogService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.auditLogService = auditLogService;
    }

    /**
     * 管理员重置用户密码（教师/学生忘记密码时由管理员操作）
     * <p>
     * 重置后 must_change_password = true，用户下次登录必须改密。
     */
    @PostMapping("/{userId}/reset-password")
    public ApiResponse<Map<String, Object>> resetPassword(
            @PathVariable UUID userId,
            @Valid @RequestBody ResetPasswordRequest request,
            Authentication authentication) {
        TenantContext ctx = (TenantContext) authentication.getDetails();

        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "用户不存在");
        }
        // 只能重置同租户用户
        if (!user.getTenantId().equals(ctx.tenantId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权操作其他租户用户");
        }

        // 更新密码 + 强制改密标记
        User update = new User();
        update.setUserId(userId);
        update.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        update.setMustChangePassword(true);
        update.setPasswordChangedAt(Instant.now());
        userMapper.updateById(update);

        // 审计
        auditLogService.log(ctx.tenantId(), ctx.userId(), "RESET_PASSWORD", "user", userId, null);

        return ApiResponse.ok(Map.of(
                "userId", userId,
                "displayName", user.getPseudonym(),
                "message", "密码已重置，用户下次登录需修改密码"
        ));
    }

    public record ResetPasswordRequest(
            @NotBlank(message = "新密码不能为空")
            @Size(min = 8, max = 64, message = "密码长度 8-64 位")
            String newPassword
    ) {}
}
