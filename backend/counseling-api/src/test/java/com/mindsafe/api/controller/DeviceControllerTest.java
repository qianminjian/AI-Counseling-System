package com.mindsafe.api.controller;

import com.mindsafe.common.exception.BizException;
import com.mindsafe.api.dto.device.BindDeviceRequest;
import com.mindsafe.domain.entity.DeviceBinding;
import com.mindsafe.domain.util.DeviceCodeUtil;
import com.mindsafe.service.device.DeviceSecurityService;
import com.mindsafe.service.device.DeviceService;
import com.mindsafe.service.device.DeviceVoiceprintService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
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
 * DeviceController 单元测试（CFG-001/004，doing/84 §六.2）
 * <p>
 * 仅验证 HTTP 编排与错误映射（404 设备不存在 / 400 业务异常），业务逻辑
 * 由 DeviceServiceTest 覆盖（PlatformControllerTest 同构模式）。
 */
class DeviceControllerTest {

    private DeviceService deviceService;
    private DeviceVoiceprintService voiceprintService;
    private DeviceSecurityService securityService;
    private DeviceController controller;

    private final String deviceCode = DeviceCodeUtil.generate("BB-2026-000123");

    @BeforeEach
    void setUp() {
        deviceService = mock(DeviceService.class);
        voiceprintService = mock(DeviceVoiceprintService.class);
        securityService = mock(DeviceSecurityService.class);
        when(securityService.enforceSignature(any(), any(), any(), any(), any())).thenReturn(true);
        controller = new DeviceController(deviceService, voiceprintService, securityService, new ObjectMapper());
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
        assertThatThrownBy(() -> controller.getDeviceInfo(deviceCode))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未找到该设备");
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
        assertThatThrownBy(() -> controller.getDeviceStatus(deviceCode)).isInstanceOf(BizException.class);
    }

    // ===== 绑定类端点：业务异常转 400 =====

    @Test
    @DisplayName("bind-code：业务异常转 400")
    void bindCodeBusinessError() {
        when(deviceService.createBindCode(deviceCode, "t"))
                .thenThrow(new IllegalArgumentException("设备已绑定"));
        // AD-007：异常上抛由 GlobalExceptionHandler 统一转 400
        assertThatThrownBy(() -> controller.createBindCode(deviceCode, "t"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已绑定");
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

        // AD-007：wrap 移除后异常上抛，由 GlobalExceptionHandler 统一转 400（本层断言抛出）
        assertThatThrownBy(() -> controller.bind(deviceCode, request, "t"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("验证码错误");
    }

    // ===== 设备上报端点：404 与成功 =====

    @Test
    @DisplayName("report/online：首次上线注册成功")
    void reportOnlineOk() {
        var body = new DeviceController.ReportOnlineRequest(deviceCode, "BB-2026-000123", null, null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ONLINE_UNBOUND");
        when(deviceService.reportOnline(deviceCode, "BB-2026-000123", null, null, null)).thenReturn(result);

        var response = controller.reportOnline(new DeviceController.ReportOnlineRequest(deviceCode, "BB-2026-000123", null, null), null);
        assertThat(response.code()).isEqualTo(0);
        assertThat(response.data().get("status")).isEqualTo("ONLINE_UNBOUND");
    }

    @Test
    @DisplayName("report/heartbeat：设备不存在返回 404")
    void heartbeatNotFound() {
        var body = new DeviceController.DeviceCodeRequest(deviceCode, null);
        when(deviceService.exists(deviceCode)).thenReturn(false);
        assertThatThrownBy(() -> controller.heartbeat(body, null, null, null)).isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("report/heartbeat：存在则更新心跳")
    void heartbeatOk() {
        var body = new DeviceController.DeviceCodeRequest(deviceCode, null);
        when(deviceService.exists(deviceCode)).thenReturn(true);
        var response = controller.heartbeat(body, null, null, null);
        assertThat(response.code()).isEqualTo(0);
        verify(deviceService).heartbeat(deviceCode);
    }

    @Test
    @DisplayName("config/pull：返回服务器配置")
    void pullConfigOk() {
        var body = new DeviceController.DeviceCodeRequest(deviceCode, null);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("serverUrl", "https://mindsafe.local");
        when(deviceService.exists(deviceCode)).thenReturn(true);
        when(deviceService.pullConfig(deviceCode, null)).thenReturn(config);

        var response = controller.pullConfig(body, null);
        assertThat(response.code()).isEqualTo(0);
        assertThat(response.data().get("serverUrl")).isEqualTo("https://mindsafe.local");
    }

    // ===== CFG-006/008（P1）：声纹编排 + 设备列表 =====

    @Test
    @DisplayName("list：按绑定归属返回设备列表")
    void listDevicesOk() {
        UUID targetId = UUID.randomUUID();
        when(deviceService.listDevices("CLASS", targetId)).thenReturn(List.of());

        var response = controller.listDevices("CLASS", targetId.toString());
        assertThat(response.code()).isEqualTo(0);
        assertThat(response.data()).isEmpty();
        verify(deviceService).listDevices("CLASS", targetId);
    }

    @Test
    @DisplayName("BUG-T-09-01 回归：list 缺参（挂载无参请求）→ 200 空列表，不再 500")
    void listDevicesMissingParams() {
        var response = controller.listDevices(null, null);
        assertThat(response.code()).isEqualTo(0);
        assertThat(response.data()).isEmpty();
    }

    @Test
    @DisplayName("BUG-T-09-01 回归：list 非 UUID 归属 ID → IllegalArgumentException（全局转 400），不再落兜底 500")
    void listDevicesInvalidUuid() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> controller.listDevices("SCHOOL", "1"));
    }

    @Test
    @DisplayName("createVoiceprintTask：studentId 缺失返回 400")
    void createVoiceprintTaskMissingStudent() {
        assertThatThrownBy(() -> controller.createVoiceprintTask(
                deviceCode, new DeviceController.VoiceprintTaskRequest(null), "t"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("studentId 缺失");
    }

    @Test
    @DisplayName("createVoiceprintTask：发起成功返回 INITIATED 任务")
    void createVoiceprintTaskOk() {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("taskId", "t1");
        task.put("phase", "INITIATED");
        when(deviceService.exists(deviceCode)).thenReturn(true);
        when(voiceprintService.createTask(deviceCode, "stu-1", "t")).thenReturn(task);

        var response = controller.createVoiceprintTask(deviceCode, new DeviceController.VoiceprintTaskRequest("stu-1"), "t");
        assertThat(response.code()).isEqualTo(0);
        assertThat(response.data().get("phase")).isEqualTo("INITIATED");
    }

    @Test
    @DisplayName("getVoiceprintTask：任务不存在或设备码不匹配返回 404")
    void getVoiceprintTaskNotFound() {
        when(voiceprintService.getTask("t1")).thenReturn(null);
        assertThatThrownBy(() -> controller.getVoiceprintTask(deviceCode, "t1")).isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("reportVoiceprintPhase：设备端进度上报成功")
    void reportVoiceprintPhaseOk() {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("taskId", "t1");
        task.put("phase", "COLLECTING");
        when(voiceprintService.reportPhase("t1", "COLLECTING", null)).thenReturn(task);

        var response = controller.reportVoiceprintPhase(new DeviceController.VoiceprintPhaseRequest("t1", "COLLECTING", null), null, null, null);
        assertThat(response.code()).isEqualTo(0);
        assertThat(response.data().get("phase")).isEqualTo("COLLECTING");
    }

    @Test
    @DisplayName("reportVoiceprintPhase：任务不存在返回 404")
    void reportVoiceprintPhaseNotFound() {
        when(voiceprintService.reportPhase("t1", "COLLECTING", null)).thenReturn(null);
        assertThatThrownBy(() -> controller.reportVoiceprintPhase(
                new DeviceController.VoiceprintPhaseRequest("t1", "COLLECTING", null), null, null, null))
                .isInstanceOf(BizException.class);
    }

    // ===== CFG-008 M13：设备操作端点 =====

    @Test
    @DisplayName("ota：受理成功")
    void otaOk() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", "ota");
        when(deviceService.ota(deviceCode, "admin-1")).thenReturn(result);
        var response = controller.ota(deviceCode, "admin-1");
        assertThat(response.code()).isEqualTo(0);
        assertThat(response.data().get("action")).isEqualTo("ota");
    }

    @Test
    @DisplayName("reboot：受理成功")
    void rebootOk() {
        when(deviceService.reboot(deviceCode, "admin-1")).thenReturn(Map.of("action", "reboot"));
        assertThat(controller.reboot(deviceCode, "admin-1").code()).isEqualTo(0);
        verify(deviceService).reboot(deviceCode, "admin-1");
    }

    @Test
    @DisplayName("factory-reset：设备不存在转 400")
    void factoryResetError() {
        when(deviceService.factoryReset(deviceCode, "admin-1"))
                .thenThrow(new IllegalArgumentException("设备不存在"));
        // AD-007：异常上抛由 GlobalExceptionHandler 统一转 400
        assertThatThrownBy(() -> controller.factoryReset(deviceCode, "admin-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("设备不存在");
    }
}
