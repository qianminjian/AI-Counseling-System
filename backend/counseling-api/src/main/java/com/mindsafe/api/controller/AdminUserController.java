package com.mindsafe.api.controller;

import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.domain.entity.User;
import com.mindsafe.service.user.AdminUserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

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

    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    /**
     * 管理员重置用户密码（教师/学生忘记密码时由管理员操作）
     * <p>
     * 重置后 must_change_password = true，用户下次登录必须改密。
     * T4 批次B：归属校验 + 更新 + 审计整体下沉 AdminUserService（事务内）。
     */
    @PostMapping("/{userId}/reset-password")
    public ApiResponse<Map<String, Object>> resetPassword(
            @PathVariable UUID userId,
            @Valid @RequestBody ResetPasswordRequest request,
            Authentication authentication) {
        TenantContext ctx = (TenantContext) authentication.getDetails();

        User user = adminUserService.resetPassword(
                ctx.tenantId(), ctx.userId(), userId, request.newPassword());

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
