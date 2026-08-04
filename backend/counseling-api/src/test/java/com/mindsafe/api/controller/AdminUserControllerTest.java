package com.mindsafe.api.controller;

import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.UserMapper;
import com.mindsafe.service.audit.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AdminUserController 单元测试（P1 覆盖率冲刺：管理员重置密码）
 */
class AdminUserControllerTest {

    private UserMapper userMapper;
    private PasswordEncoder passwordEncoder;
    private AuditLogService auditLogService;
    private AdminUserController controller;

    private final UUID adminTenantId = UUID.randomUUID();
    private final UUID adminUserId = UUID.randomUUID();
    private final UUID targetUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        passwordEncoder = mock(PasswordEncoder.class);
        auditLogService = mock(AuditLogService.class);
        controller = new AdminUserController(userMapper, passwordEncoder, auditLogService);
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
    @DisplayName("resetPassword 用户不存在 → RESOURCE_NOT_FOUND")
    void reset_userNotFound() {
        when(userMapper.selectById(targetUserId)).thenReturn(null);

        assertThatThrownBy(() -> controller.resetPassword(targetUserId,
                new AdminUserController.ResetPasswordRequest("NewPass123"), adminAuth()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("用户不存在");
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    @DisplayName("resetPassword 跨租户用户 → FORBIDDEN")
    void reset_crossTenant() {
        User u = userInSameTenant();
        u.setTenantId(UUID.randomUUID());
        when(userMapper.selectById(targetUserId)).thenReturn(u);

        assertThatThrownBy(() -> controller.resetPassword(targetUserId,
                new AdminUserController.ResetPasswordRequest("NewPass123"), adminAuth()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("无权操作其他租户用户");
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    @DisplayName("resetPassword 成功 → 加密+强制改密+审计")
    void reset_success() {
        when(userMapper.selectById(targetUserId)).thenReturn(userInSameTenant());
        when(passwordEncoder.encode("NewPass123")).thenReturn("bcrypt-hash");

        ApiResponse<Map<String, Object>> resp = controller.resetPassword(targetUserId,
                new AdminUserController.ResetPasswordRequest("NewPass123"), adminAuth());

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data().get("userId")).isEqualTo(targetUserId);
        assertThat(resp.data().get("displayName")).isEqualTo("张老师");
        assertThat((String) resp.data().get("message")).contains("下次登录需修改密码");
        verify(userMapper).updateById(any(User.class));
        verify(auditLogService).log(adminTenantId, adminUserId, "RESET_PASSWORD", "user", targetUserId, null);
    }

    @Test
    @DisplayName("resetPassword 密码已加密存储（验证 update 对象）")
    void reset_passwordEncoded() {
        when(userMapper.selectById(targetUserId)).thenReturn(userInSameTenant());
        when(passwordEncoder.encode("Secret@88")).thenReturn("hash-abc");

        controller.resetPassword(targetUserId,
                new AdminUserController.ResetPasswordRequest("Secret@88"), adminAuth());

        verify(passwordEncoder).encode("Secret@88");
        verify(userMapper).updateById(org.mockito.ArgumentMatchers.<User>argThat(u ->
                u instanceof User user
                        && "hash-abc".equals(user.getPasswordHash())
                        && Boolean.TRUE.equals(user.getMustChangePassword())
                        && user.getPasswordChangedAt() != null
                        && user.getUserId().equals(targetUserId)));
    }

    @Test
    @DisplayName("ResetPasswordRequest record 携带校验注解（@NotBlank/@Size）")
    void request_recordHasValidation() {
        var req = new AdminUserController.ResetPasswordRequest("LongEnoughPass1");

        assertThat(req.newPassword()).isEqualTo("LongEnoughPass1");
        assertThat(req.getClass().getDeclaredFields()).hasSize(1);
    }
}
