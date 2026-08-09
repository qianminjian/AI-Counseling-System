package com.mindsafe.service.platform;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.PlatformAdmin;
import com.mindsafe.domain.mapper.PlatformAdminMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 平台管理员登录单元测试（ADMIN-P0-02，AC-P0-02）
 * 覆盖：登录成功/last_login_at 留痕/密码错误/用户不存在/禁用账号
 */
class PlatformAdminServiceTest {

    private final PlatformAdminMapper mapper = mock(PlatformAdminMapper.class);
    private final PlatformAdminService service =
            new PlatformAdminService(mapper, new BCryptPasswordEncoder());

    private PlatformAdmin activeAdmin(String username, String rawPassword) {
        PlatformAdmin admin = new PlatformAdmin();
        admin.setAdminId(UUID.randomUUID());
        admin.setUsername(username);
        admin.setPasswordHash(new BCryptPasswordEncoder().encode(rawPassword));
        admin.setRole(PlatformAdmin.ROLE_OPS_ADMIN);
        admin.setDisplayName("运维管理员");
        admin.setStatus(PlatformAdmin.STATUS_ACTIVE);
        return admin;
    }

    @Test
    @DisplayName("登录成功 → 返回管理员 + last_login_at 留痕")
    void loginSuccess() {
        PlatformAdmin admin = activeAdmin("ops", "secret123");
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(admin);

        PlatformAdmin result = service.login("ops", "secret123");

        assertThat(result.getUsername()).isEqualTo("ops");
        assertThat(result.getRole()).isEqualTo(PlatformAdmin.ROLE_OPS_ADMIN);
        assertThat(result.getLastLoginAt()).isNotNull();
        verify(mapper).updateById(admin);
    }

    @Test
    @DisplayName("密码错误 → UNAUTHORIZED（不泄露账号存在性）")
    void loginWrongPassword() {
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(activeAdmin("ops", "secret123"));

        assertThatThrownBy(() -> service.login("ops", "wrong-pass"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.UNAUTHORIZED.code()));
        verify(mapper, never()).updateById(any(PlatformAdmin.class));
    }

    @Test
    @DisplayName("用户名不存在 → UNAUTHORIZED（与密码错误同码，防枚举）")
    void loginUnknownUser() {
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(null);

        assertThatThrownBy(() -> service.login("ghost", "whatever"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.UNAUTHORIZED.code()));
        verify(mapper, never()).updateById(any(PlatformAdmin.class));
    }

    @Test
    @DisplayName("禁用账号 → UNAUTHORIZED（拒绝登录）")
    void loginDisabledAccount() {
        PlatformAdmin admin = activeAdmin("ops", "secret123");
        admin.setStatus(PlatformAdmin.STATUS_DISABLED);
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(admin);

        assertThatThrownBy(() -> service.login("ops", "secret123"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.UNAUTHORIZED.code()));
        verify(mapper, never()).updateById(any(PlatformAdmin.class));
    }
}
