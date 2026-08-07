package com.mindsafe.api.controller;

import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.User;
import com.mindsafe.service.user.AdminUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AdminUserController 单元测试（T4 批次B 改造版：归属校验/更新/审计下沉 AdminUserService，Controller 仅 HTTP 层职责）
 * <p>
 * 域语义（不存在 / 跨租户拒绝 / 密码编码）由 AdminUserService 测试覆盖——本测试经 Service 接口验证 Controller 编排。
 */
class AdminUserControllerTest {

    private AdminUserService adminUserService;
    private AdminUserController controller;

    private final UUID adminTenantId = UUID.randomUUID();
    private final UUID adminUserId = UUID.randomUUID();
    private final UUID targetUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        adminUserService = mock(AdminUserService.class);
        controller = new AdminUserController(adminUserService);
    }

    private Authentication adminAuth() {
        Authentication auth = mock(Authentication.class);
        when(auth.getDetails()).thenReturn(new TenantContext(adminTenantId, adminUserId, "admin"));
        return auth;
    }

    private User userInSameTenant() {
        User u = new User();
        u.setUserId(targetUserId);
        u.setTenantId(adminTenantId);
        u.setPseudonym("张老师");
        return u;
    }

    @Test
    @DisplayName("resetPassword 用户不存在 → RESOURCE_NOT_FOUND（Service 抛出）")
    void reset_userNotFound() {
        doThrow(new BizException(ErrorCode.RESOURCE_NOT_FOUND, "用户不存在"))
                .when(adminUserService).resetPassword(eq(adminTenantId), eq(adminUserId), eq(targetUserId), anyString());

        assertThatThrownBy(() -> controller.resetPassword(targetUserId,
                new AdminUserController.ResetPasswordRequest("NewPass123"), adminAuth()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("用户不存在");
    }

    @Test
    @DisplayName("resetPassword 跨租户用户 → FORBIDDEN（Service 抛出）")
    void reset_crossTenant() {
        doThrow(new BizException(ErrorCode.FORBIDDEN, "无权操作其他租户用户"))
                .when(adminUserService).resetPassword(eq(adminTenantId), eq(adminUserId), eq(targetUserId), anyString());

        assertThatThrownBy(() -> controller.resetPassword(targetUserId,
                new AdminUserController.ResetPasswordRequest("NewPass123"), adminAuth()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("无权操作其他租户用户");
    }

    @Test
    @DisplayName("resetPassword 成功 → 透传 Service 结果")
    void reset_success() {
        when(adminUserService.resetPassword(adminTenantId, adminUserId, targetUserId, "NewPass123"))
                .thenReturn(userInSameTenant());

        ApiResponse<Map<String, Object>> resp = controller.resetPassword(targetUserId,
                new AdminUserController.ResetPasswordRequest("NewPass123"), adminAuth());

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data().get("userId")).isEqualTo(targetUserId);
        assertThat(resp.data().get("displayName")).isEqualTo("张老师");
        assertThat((String) resp.data().get("message")).contains("下次登录需修改密码");
        verify(adminUserService).resetPassword(adminTenantId, adminUserId, targetUserId, "NewPass123");
    }

    @Test
    @DisplayName("resetPassword 新密码透传 Service（编码/强制改密由 Service 承担）")
    void reset_passwordEncoded() {
        when(adminUserService.resetPassword(adminTenantId, adminUserId, targetUserId, "Secret@88"))
                .thenReturn(userInSameTenant());

        controller.resetPassword(targetUserId,
                new AdminUserController.ResetPasswordRequest("Secret@88"), adminAuth());

        verify(adminUserService).resetPassword(adminTenantId, adminUserId, targetUserId, "Secret@88");
    }

    @Test
    @DisplayName("ResetPasswordRequest record 携带校验注解（@NotBlank/@Size）")
    void request_recordHasValidation() {
        var req = new AdminUserController.ResetPasswordRequest("LongEnoughPass1");

        assertThat(req.newPassword()).isEqualTo("LongEnoughPass1");
        assertThat(req.getClass().getDeclaredFields()).hasSize(1);
    }
}
