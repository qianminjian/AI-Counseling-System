package com.mindsafe.api.controller;

import com.mindsafe.domain.entity.SysConfig;
import com.mindsafe.service.config.SysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PlatformConfigController 单元测试（ADMIN-P1-01：配置注册表/详情/修改/历史 HTTP 编排）
 */
class PlatformConfigControllerTest {

    private SysConfigService service;
    private PlatformConfigController controller;

    @BeforeEach
    void setUp() {
        service = mock(SysConfigService.class);
        controller = new PlatformConfigController(service);
    }

    @Test
    @DisplayName("GET /registry → 注册表透传（分域过滤）")
    void registry() {
        SysConfig config = new SysConfig();
        config.setConfigKey("mindsafe.test.key");
        when(service.listByDomain("security")).thenReturn(List.of(config));

        var response = controller.registry("security");

        assertThat(response.data()).hasSize(1);
        assertThat(response.data().get(0).getConfigKey()).isEqualTo("mindsafe.test.key");
    }

    @Test
    @DisplayName("POST /{key} → 修改透传（value/reason）")
    void update() {
        SysConfig config = new SysConfig();
        config.setConfigKey("mindsafe.test.key");
        when(service.update(eq("mindsafe.test.key"), eq("v1"), eq("调优"), any())).thenReturn(config);

        var response = controller.update("mindsafe.test.key",
                new PlatformConfigController.ConfigUpdateRequest("v1", "调优"));

        assertThat(response.data().getConfigKey()).isEqualTo("mindsafe.test.key");
        verify(service).update(eq("mindsafe.test.key"), eq("v1"), eq("调优"), any());
    }

    @Test
    @DisplayName("GET /{key}/history → 变更历史透传")
    void history() {
        when(service.history("mindsafe.test.key", 50)).thenReturn(List.of());

        var response = controller.history("mindsafe.test.key", 50);

        assertThat(response.data()).isEmpty();
        verify(service).history("mindsafe.test.key", 50);
    }
}
