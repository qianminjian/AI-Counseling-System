package com.mindsafe.service.user;

import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.UserMapper;
import com.mindsafe.service.audit.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AdminUserService 管理端用户服务单测：密码重置（同租户归属校验 + 强制改密标记 + 审计）。
 */
@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    @Mock private UserMapper userMapper;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuditLogService auditLogService;

    private AdminUserService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID operatorId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AdminUserService(userMapper, passwordEncoder, auditLogService);
    }

    @Test
    @DisplayName("重置成功：编码密码 + 强制改密 + 审计")
    void resetPassword_success() {
        User target = new User();
        target.setUserId(userId);
        target.setTenantId(tenantId);
        when(userMapper.selectById(userId)).thenReturn(target);
        when(passwordEncoder.encode("newPass123")).thenReturn("encoded-hash");

        User result = service.resetPassword(tenantId, operatorId, userId, "newPass123");

        assertThat(result).isSameAs(target);
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(captor.capture());
        User update = captor.getValue();
        assertThat(update.getUserId()).isEqualTo(userId);
        assertThat(update.getPasswordHash()).isEqualTo("encoded-hash");
        assertThat(update.getMustChangePassword()).isTrue();
        assertThat(update.getPasswordChangedAt()).isNotNull();
        verify(auditLogService).log(eq(tenantId), eq(operatorId), eq("RESET_PASSWORD"), eq("user"), eq(userId), eq(null));
    }

    @Test
    @DisplayName("用户不存在 → RESOURCE_NOT_FOUND")
    void resetPassword_userNotFound() {
        when(userMapper.selectById(userId)).thenReturn(null);

        assertThatThrownBy(() -> service.resetPassword(tenantId, operatorId, userId, "x"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    @DisplayName("跨租户用户 → FORBIDDEN，不更新不审计")
    void resetPassword_crossTenant() {
        User target = new User();
        target.setUserId(userId);
        target.setTenantId(UUID.randomUUID()); // 其他租户
        when(userMapper.selectById(userId)).thenReturn(target);

        assertThatThrownBy(() -> service.resetPassword(tenantId, operatorId, userId, "x"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(userMapper, never()).updateById(any(User.class));
        verify(auditLogService, never()).log(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
