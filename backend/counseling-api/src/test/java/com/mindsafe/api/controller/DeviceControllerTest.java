package com.mindsafe.api.controller;

import com.mindsafe.api.dto.device.BindDeviceRequest;
import com.mindsafe.domain.entity.DeviceBinding;
import com.mindsafe.domain.util.DeviceCodeUtil;
import com.mindsafe.service.device.DeviceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DeviceController 单元测试（CFG-001/004，doing/84 §六.2）
 * <p>
 * 仅验证 HTTP 编排与错误映射（404 设备不存在 / 400 业务异常），业务逻辑
 * 由 DeviceServiceTest 覆盖（PlatformControllerTest 同构模式）。
 */
class DeviceControllerTest {

    private DeviceService deviceService;
    private DeviceController controller;

    private final String deviceCode = DeviceCodeUtil.generate("BB-2026-000123");

    @BeforeEach
    void setUp() {
        deviceService = mock(DeviceService.class);
        controller = new DeviceController(deviceService);
    }

    private Map<String, Object> sampleInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("deviceCode", deviceCode);
        info.put("deviceType", "desk_toy");
        info.put("codeTail", deviceCode.substring(deviceCode.length() - 4));
        info.put("bound", false);
        return info;
    }

    // ===== info/status：404 分流 =====

    @Test
    @DisplayName("设备不存在时 info 返回 404 与引导文案")
    void infoNotFound() {
        when(deviceService.exists(deviceCode)).thenReturn(false);
        var response = controller.getDeviceInfo(deviceCode);
        assertThat(response.code()).isEqualTo(404);
        assertThat(response.message()).contains("未找到该设备");
        verify(deviceService, never()).getDeviceInfo(deviceCode);
    }

    @Test
    @DisplayName("设备存在时 info 返回脱敏数据")
    void infoOk() {
        when(deviceService.exists(deviceCode)).thenReturn(true);
        when(deviceService.getDeviceInfo(deviceCode)).thenReturn(sampleInfo());
        var response = controller.getDeviceInfo(deviceCode);
        assertThat(response.code()).isEqualTo(0);
        assertThat(response.data().get("codeTail")).isNotNull();
    }

    @Test
    @DisplayName("status 轮询：设备不存在返回 404")
    void statusNotFound() {
        when(deviceService.exists(deviceCode)).thenReturn(false);
        assertThat(controller.getDeviceStatus(deviceCode).code()).isEqualTo(404);
    }

    // ===== 绑定类端点：业务异常转 400 =====

    @Test
    @DisplayName("bind-code：业务异常转 400")
    void bindCodeBusinessError() {
        when(deviceService.createBindCode(deviceCode, "t"))
                .thenThrow(new IllegalArgumentException("设备已绑定"));
        var response = controller.createBindCode(deviceCode, "t");
        assertThat(response.code()).isEqualTo(400);
        assertThat(response.message()).contains("已绑定");
    }

    @Test
    @DisplayName("bind：成功返回 ONLINE_BOUND")
    void bindOk() {
        BindDeviceRequest request = new BindDeviceRequest();
        request.setBindType(DeviceBinding.BIND_TYPE_CLASS);
        request.setBindTargetId(UUID.randomUUID());
        request.setCode("123456");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ONLINE_BOUND");
        when(deviceService.bind(deviceCode, request.getBindType(), request.getBindTargetId(),
                null, "123456", "t")).thenReturn(result);

        var response = controller.bind(deviceCode, request, "t");
        assertThat(response.code()).isEqualTo(0);
        assertThat(response.data().get("status")).isEqualTo("ONLINE_BOUND");
    }

    @Test
    @DisplayName("bind：验证码错误转 400")
    void bindCodeError() {
        BindDeviceRequest request = new BindDeviceRequest();
        request.setBindType(DeviceBinding.BIND_TYPE_CLASS);
        request.setBindTargetId(UUID.randomUUID());
        request.setCode("000000");
        when(deviceService.bind(deviceCode, request.getBindType(), request.getBindTargetId(),
                null, "000000", "t")).thenThrow(new IllegalArgumentException("验证码错误"));

        var response = controller.bind(deviceCode, request, "t");
        assertThat(response.code()).isEqualTo(400);
        assertThat(response.message()).contains("验证码错误");
    }

    // ===== 设备上报端点：404 与成功 =====

    @Test
    @DisplayName("report/online：首次上线注册成功")
    void reportOnlineOk() {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("deviceCode", deviceCode);
        body.put("sn", "BB-2026-000123");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ONLINE_UNBOUND");
        when(deviceService.reportOnline(deviceCode, "BB-2026-000123", null, null)).thenReturn(result);

        var response = controller.reportOnline(body);
        assertThat(response.code()).isEqualTo(0);
        assertThat(response.data().get("status")).isEqualTo("ONLINE_UNBOUND");
    }

    @Test
    @DisplayName("report/heartbeat：设备不存在返回 404")
    void heartbeatNotFound() {
        Map<String, String> body = Map.of("deviceCode", deviceCode);
        when(deviceService.exists(deviceCode)).thenReturn(false);
        assertThat(controller.heartbeat(body).code()).isEqualTo(404);
    }

    @Test
    @DisplayName("report/heartbeat：存在则更新心跳")
    void heartbeatOk() {
        Map<String, String> body = Map.of("deviceCode", deviceCode);
        when(deviceService.exists(deviceCode)).thenReturn(true);
        var response = controller.heartbeat(body);
        assertThat(response.code()).isEqualTo(0);
        verify(deviceService).heartbeat(deviceCode);
    }

    @Test
    @DisplayName("config/pull：返回服务器配置")
    void pullConfigOk() {
        Map<String, String> body = Map.of("deviceCode", deviceCode);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("serverUrl", "https://mindsafe.local");
        when(deviceService.exists(deviceCode)).thenReturn(true);
        when(deviceService.pullConfig(deviceCode)).thenReturn(config);

        var response = controller.pullConfig(body);
        assertThat(response.code()).isEqualTo(0);
        assertThat(response.data().get("serverUrl")).isEqualTo("https://mindsafe.local");
    }
}
