package com.mindsafe.api.controller;

import com.mindsafe.api.security.JwtTokenProvider;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.PlatformAdmin;
import com.mindsafe.service.platform.PlatformAdminService;
import com.mindsafe.service.platform.PlatformLoginGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 平台管理员认证端点单元测试（ADMIN-P0-02 + P0 backlog M3：登录/防爆破/XFF 取尾）
 */
class PlatformAuthControllerTest {

    private PlatformAdminService adminService;
    private JwtTokenProvider jwtTokenProvider;
    private PlatformLoginGuard loginGuard;
    private HttpServletRequest request;
    private PlatformAuthController controller;

    @BeforeEach
    void setUp() {
        adminService = mock(PlatformAdminService.class);
        jwtTokenProvider = mock(JwtTokenProvider.class);
        loginGuard = mock(PlatformLoginGuard.class);
        request = mock(HttpServletRequest.class);
        controller = new PlatformAuthController(adminService, jwtTokenProvider, loginGuard);
    }

    private PlatformAuthController.PlatformLoginRequest req(String u, String p) {
        return new PlatformAuthController.PlatformLoginRequest(u, p);
    }

    @Test
    @DisplayName("登录成功：签发 PLATFORM_ token + 记录成功 + 返回角色")
    void loginSuccess() {
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
        when(loginGuard.isLocked("10.0.0.1")).thenReturn(false);
        PlatformAdmin admin = new PlatformAdmin();
        admin.setAdminId(UUID.randomUUID());
        admin.setRole(PlatformAdmin.ROLE_OPS_ADMIN);
        admin.setDisplayName("运维");
        when(adminService.login("ops", "secret")).thenReturn(admin);
        when(jwtTokenProvider.generatePlatformToken(admin.getAdminId(), admin.getRole()))
                .thenReturn("PLATFORM_abc");

        var response = controller.login(req("ops", "secret"), request);

        assertThat(response.data().token()).isEqualTo("PLATFORM_abc");
        assertThat(response.data().role()).isEqualTo(PlatformAdmin.ROLE_OPS_ADMIN);
        assertThat(response.data().displayName()).isEqualTo("运维");
        verify(loginGuard).recordSuccess("10.0.0.1");
    }

    @Test
    @DisplayName("锁定期间拒绝登录（防爆破 M3）")
    void loginRejectedWhenLocked() {
        when(request.getRemoteAddr()).thenReturn("10.0.0.2");
        when(loginGuard.isLocked("10.0.0.2")).thenReturn(true);

        assertThatThrownBy(() -> controller.login(req("ops", "secret"), request))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED.code());
        verify(adminService, never()).login(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("登录失败：记录失败计数（供防爆破累计）")
    void loginFailureRecordsFailure() {
        when(request.getRemoteAddr()).thenReturn("10.0.0.3");
        when(loginGuard.isLocked("10.0.0.3")).thenReturn(false);
        when(adminService.login("ops", "wrong")).thenThrow(
                new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误"));

        assertThatThrownBy(() -> controller.login(req("ops", "wrong"), request))
                .isInstanceOf(BizException.class);
        verify(loginGuard).recordFailure("10.0.0.3");
    }

    @Test
    @DisplayName("客户端 IP：XFF 取尾元素（nginx 追加的 $remote_addr 不可伪造）")
    void clientIpUsesXffTail() {
        when(request.getHeader("X-Forwarded-For")).thenReturn("1.2.3.4, 10.0.0.5");
        when(loginGuard.isLocked("10.0.0.5")).thenReturn(false);
        PlatformAdmin admin = new PlatformAdmin();
        admin.setAdminId(UUID.randomUUID());
        admin.setRole(PlatformAdmin.ROLE_SUPER_ADMIN);
        when(adminService.login("admin", "x")).thenReturn(admin);
        when(jwtTokenProvider.generatePlatformToken(admin.getAdminId(), admin.getRole()))
                .thenReturn("PLATFORM_x");

        controller.login(req("admin", "x"), request);

        // 锁定与失败计数都按尾元素 IP 计算（伪造首元素无效）
        verify(loginGuard).recordSuccess("10.0.0.5");
    }
}
