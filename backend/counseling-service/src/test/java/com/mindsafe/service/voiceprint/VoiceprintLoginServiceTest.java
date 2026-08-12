package com.mindsafe.service.voiceprint;

import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.UserMapper;
import com.mindsafe.service.auth.TenantAccessGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VoiceprintLoginService 测试（板块03 P1-6 补测：声纹登录门禁 + 最后登录时间）
 * <p>
 * 覆盖：登录门禁三条件（账号存在 / ACTIVE / 租户可登录）缺一即拒、
 * touchLastLogin 仅更新时间戳字段。
 */
class VoiceprintLoginServiceTest {

    private UserMapper userMapper;
    private TenantAccessGuard tenantAccessGuard;
    private VoiceprintLoginService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        tenantAccessGuard = mock(TenantAccessGuard.class);
        service = new VoiceprintLoginService(userMapper, tenantAccessGuard);
    }

    private User activeUser() {
        User u = new User();
        u.setUserId(userId);
        u.setTenantId(tenantId);
        u.setStatus(User.STATUS_ACTIVE);
        return u;
    }

    @Test
    @DisplayName("账号 ACTIVE + 租户可登录 → 返回用户")
    void findLoginAllowedUserReturnsUser() {
        when(userMapper.selectById(userId)).thenReturn(activeUser());
        when(tenantAccessGuard.isLoginAllowed(tenantId)).thenReturn(true);

        User result = service.findLoginAllowedUser(userId);

        assertThat(result).isNotNull();
        assertThat(result.getUserId()).isEqualTo(userId);
    }

    @Test
    @DisplayName("账号不存在 → null（租户门禁不调用）")
    void missingUserReturnsNull() {
        when(userMapper.selectById(userId)).thenReturn(null);

        assertThat(service.findLoginAllowedUser(userId)).isNull();
        verify(tenantAccessGuard, never()).isLoginAllowed(any());
    }

    @Test
    @DisplayName("账号非 ACTIVE（停用）→ null")
    void disabledUserReturnsNull() {
        User u = activeUser();
        u.setStatus("disabled");
        when(userMapper.selectById(userId)).thenReturn(u);
        when(tenantAccessGuard.isLoginAllowed(tenantId)).thenReturn(true);

        assertThat(service.findLoginAllowedUser(userId)).isNull();
    }

    @Test
    @DisplayName("租户不可登录 → null")
    void tenantNotLoginAllowedReturnsNull() {
        when(userMapper.selectById(userId)).thenReturn(activeUser());
        when(tenantAccessGuard.isLoginAllowed(tenantId)).thenReturn(false);

        assertThat(service.findLoginAllowedUser(userId)).isNull();
    }

    @Test
    @DisplayName("touchLastLogin：仅更新最后登录时间，不影响其他字段")
    void touchLastLoginUpdatesTimestampOnly() {
        service.touchLastLogin(userId);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(userId);
        assertThat(captor.getValue().getLastLoginAt()).isNotNull();
    }
}
