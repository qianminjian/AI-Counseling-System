package com.mindsafe.api.controller;

import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.service.device.DevicePreferenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TocPreferenceController 测试（doing/85 TOC-006）
 * 覆盖：查询（无偏好 404）、设置（家庭隔离 + 音量校验 400）。
 */
class TocPreferenceControllerTest {

    private DevicePreferenceService preferenceService;
    private TocPreferenceController controller;

    private final UUID familyAccountId = UUID.randomUUID();
    private final Authentication auth = mock(Authentication.class);

    @BeforeEach
    void setUp() {
        preferenceService = mock(DevicePreferenceService.class);
        controller = new TocPreferenceController(preferenceService);
        when(auth.getDetails()).thenReturn(new TenantContext(null, familyAccountId, "toc_parent"));
    }

    @Test
    @DisplayName("get：无偏好返回 404")
    void getNotFound() {
        when(preferenceService.getPreferences(familyAccountId, "K7M2P9XW4AQ")).thenReturn(null);
        assertThat(controller.get(auth, "K7M2P9XW4AQ").code()).isEqualTo(404);
    }

    @Test
    @DisplayName("set：保存偏好成功")
    void setOk() {
        when(preferenceService.setPreferences(familyAccountId, "K7M2P9XW4AQ", 60, "qingyu", "gentle"))
                .thenReturn(Map.of("volume", 60));
        var response = controller.set(auth, "K7M2P9XW4AQ",
                Map.of("volume", 60, "voicePersona", "qingyu", "dialoguePref", "gentle"));
        assertThat(response.code()).isEqualTo(0);
        assertThat(response.data().get("volume")).isEqualTo(60);
        verify(preferenceService).setPreferences(familyAccountId, "K7M2P9XW4AQ", 60, "qingyu", "gentle");
    }

    @Test
    @DisplayName("set：音量越界转 400")
    void setInvalidVolume() {
        when(preferenceService.setPreferences(familyAccountId, "K7M2P9XW4AQ", 150, null, null))
                .thenThrow(new IllegalArgumentException("音量范围为 0-100"));
        var response = controller.set(auth, "K7M2P9XW4AQ", Map.of("volume", 150));
        assertThat(response.code()).isEqualTo(400);
        assertThat(response.message()).contains("0-100");
    }
}
