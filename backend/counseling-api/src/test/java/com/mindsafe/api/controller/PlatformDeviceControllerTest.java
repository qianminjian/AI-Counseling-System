package com.mindsafe.api.controller;

import com.mindsafe.service.device.PlatformDeviceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PlatformDeviceController 测试（CFG-008 admin-web M13）
 * <p>
 * 仅验证 HTTP 编排与错误映射（404/400），业务逻辑由 PlatformDeviceServiceTest 覆盖。
 */
class PlatformDeviceControllerTest {

    private PlatformDeviceService service;
    private PlatformDeviceController controller;

    @BeforeEach
    void setUp() {
        service = mock(PlatformDeviceService.class);
        controller = new PlatformDeviceController(service);
    }

    @Test
    @DisplayName("list：跨租户设备列表（状态/归属筛选）")
    void listOk() {
        when(service.listDevices("ONLINE_BOUND", null)).thenReturn(List.of());
        var response = controller.listDevices("ONLINE_BOUND", null);
        assertThat(response.code()).isEqualTo(0);
        verify(service).listDevices("ONLINE_BOUND", null);
    }

    @Test
    @DisplayName("detail：设备不存在返回 404")
    void detailNotFound() {
        UUID deviceId = UUID.randomUUID();
        when(service.getDeviceDetail(deviceId)).thenReturn(null);
        assertThat(controller.getDeviceDetail(deviceId).code()).isEqualTo(404);
    }

    @Test
    @DisplayName("export-qr：deviceCodes 缺失返回 400")
    void exportQrMissingCodes() {
        var response = controller.exportQr(Map.of());
        assertThat(response.code()).isEqualTo(400);
    }

    @Test
    @DisplayName("export-qr：签发成功返回印刷包")
    void exportQrOk() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("issuedCount", 1);
        when(service.exportQr(List.of("K7M2P9XW4AQ"), "admin-1")).thenReturn(result);

        var response = controller.exportQr(Map.of("deviceCodes", List.of("K7M2P9XW4AQ"), "issuedBy", "admin-1"));
        assertThat(response.code()).isEqualTo(0);
        assertThat(response.data().get("issuedCount")).isEqualTo(1);
    }

    @Test
    @DisplayName("batch：非法 action 转 400")
    void batchInvalidAction() {
        when(service.batchOperation(List.of("K7M2P9XW4AQ"), "hack", null))
                .thenThrow(new IllegalArgumentException("非法操作类型: hack"));
        var response = controller.batchOperation(Map.of("deviceCodes", List.of("K7M2P9XW4AQ"), "action", "hack"));
        assertThat(response.code()).isEqualTo(400);
        assertThat(response.message()).contains("非法操作类型");
    }

    @Test
    @DisplayName("batch：受理成功返回结果")
    void batchOk() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("acceptedCount", 1);
        when(service.batchOperation(List.of("K7M2P9XW4AQ"), "ota", "admin-1")).thenReturn(result);
        var response = controller.batchOperation(
                Map.of("deviceCodes", List.of("K7M2P9XW4AQ"), "action", "ota", "operator", "admin-1"));
        assertThat(response.code()).isEqualTo(0);
        assertThat(response.data().get("acceptedCount")).isEqualTo(1);
    }
}
