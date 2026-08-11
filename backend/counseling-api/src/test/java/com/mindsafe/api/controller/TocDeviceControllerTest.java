package com.mindsafe.api.controller;

import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.service.toc.TocDeviceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TocDeviceController 测试（doing/85 TOC-003）
 * 覆盖：绑定/解绑/列表编排（familyAccountId 取自 token 上下文，客户端不可指定）。
 */
class TocDeviceControllerTest {

    private TocDeviceService tocDeviceService;
    private TocDeviceController controller;

    private final UUID familyAccountId = UUID.randomUUID();
    private final Authentication auth = mock(Authentication.class);

    @BeforeEach
    void setUp() {
        tocDeviceService = mock(TocDeviceService.class);
        controller = new TocDeviceController(tocDeviceService);
        when(auth.getDetails()).thenReturn(new TenantContext(null, familyAccountId, "toc_parent"));
    }

    @Test
    @DisplayName("bind：以 token 家庭账号 + 可选 profileId 绑定")
    void bindOk() {
        UUID profileId = UUID.randomUUID();
        when(tocDeviceService.bind(familyAccountId, "K7M2P9XW4AQ", profileId, "123456",
                familyAccountId.toString()))
                .thenReturn(Map.of("status", "ONLINE_BOUND"));

        var response = controller.bind(auth, "K7M2P9XW4AQ",
                Map.of("code", "123456", "profileId", profileId.toString()));

        assertThat(response.code()).isEqualTo(0);
        assertThat(response.data().get("status")).isEqualTo("ONLINE_BOUND");
        verify(tocDeviceService).bind(familyAccountId, "K7M2P9XW4AQ", profileId, "123456",
                familyAccountId.toString());
    }

    @Test
    @DisplayName("bind：服务异常转 400")
    void bindError() {
        when(tocDeviceService.bind(familyAccountId, "K7M2P9XW4AQ", null, "000000",
                familyAccountId.toString()))
                .thenThrow(new IllegalArgumentException("验证码错误"));
        // AD-007：异常上抛由 GlobalExceptionHandler 统一转 400
        assertThatThrownBy(() -> controller.bind(auth, "K7M2P9XW4AQ", Map.of("code", "000000")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("验证码");
    }

    @Test
    @DisplayName("list：返回家庭设备列表")
    void listOk() {
        when(tocDeviceService.listDevices(familyAccountId)).thenReturn(List.of());
        var response = controller.list(auth);
        assertThat(response.code()).isEqualTo(0);
        verify(tocDeviceService).listDevices(familyAccountId);
    }

    @Test
    @DisplayName("unbind：委托解绑")
    void unbindOk() {
        var response = controller.unbind(auth, "K7M2P9XW4AQ");
        assertThat(response.code()).isEqualTo(0);
        verify(tocDeviceService).unbind(familyAccountId, "K7M2P9XW4AQ", familyAccountId.toString());
    }
}
