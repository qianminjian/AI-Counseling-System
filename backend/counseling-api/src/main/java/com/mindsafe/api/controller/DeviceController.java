package com.mindsafe.api.controller;

import com.mindsafe.api.dto.device.BindDeviceRequest;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.service.device.DeviceService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 无屏终端设备管理 API（CFG-001/004，doing/84 §六.2）
 * <p>
 * 端点分组：设备业务域（扫码入口脱敏查询/绑定/解绑）+ 设备上报域
 * （report/**，设备端签名通道，白名单匿名）+ 配置拉取（config/pull）。
 * 绑定类端点需登录态（SecurityConfig 默认规则）。
 */
@RestController
@RequestMapping("/api/v1/device")
public class DeviceController {

    private final DeviceService deviceService;

    public DeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    /** 扫码入口页脱敏信息（匿名，AC-84-01）：型号/尾号/绑定态 */
    @GetMapping("/{deviceCode}/info")
    public ApiResponse<Map<String, Object>> getDeviceInfo(@PathVariable String deviceCode) {
        if (!deviceService.exists(deviceCode)) {
            return ApiResponse.error(404, "未找到该设备，请核对机身二维码");
        }
        return ApiResponse.ok(deviceService.getDeviceInfo(deviceCode));
    }

    /** 回连检查轮询（匿名，AC-84-04）：在线/离线 + 固件版本 */
    @GetMapping("/{deviceCode}/status")
    public ApiResponse<Map<String, Object>> getDeviceStatus(@PathVariable String deviceCode) {
        if (!deviceService.exists(deviceCode)) {
            return ApiResponse.error(404, "设备不存在");
        }
        return ApiResponse.ok(deviceService.getDeviceStatus(deviceCode));
    }

    /** 生成绑定验证码会话（登录态，AC-84-23）：返回明文一次（设备语音播报） */
    @PostMapping("/{deviceCode}/bind-code")
    public ApiResponse<Map<String, Object>> createBindCode(@PathVariable String deviceCode,
                                                           @RequestParam(required = false) String operator) {
        return wrap(deviceCode, () -> deviceService.createBindCode(deviceCode, operator));
    }

    /** 绑定（登录态，AC-84-10/11/12）：归属 + 验证码双因子 */
    @PostMapping("/{deviceCode}/bind")
    public ApiResponse<Map<String, Object>> bind(@PathVariable String deviceCode,
                                                 @RequestBody BindDeviceRequest request,
                                                 @RequestParam(required = false) String operator) {
        return wrap(deviceCode, () -> deviceService.bind(deviceCode, request.getBindType(),
                request.getBindTargetId(), request.getStudentId(), request.getCode(), operator));
    }

    /** 解绑（登录态，reason 由审计层记录） */
    @PostMapping("/{deviceCode}/unbind")
    public ApiResponse<Map<String, Object>> unbind(@PathVariable String deviceCode,
                                                   @RequestParam(required = false) String operator) {
        return wrap(deviceCode, () -> deviceService.unbind(deviceCode, operator));
    }

    /** 设备首次上线/回连注册（设备端上报，匿名，AC-84-24） */
    @PostMapping("/report/online")
    public ApiResponse<Map<String, Object>> reportOnline(@RequestBody Map<String, String> body) {
        return wrap(body.get("deviceCode"), () -> deviceService.reportOnline(
                body.get("deviceCode"), body.get("sn"), body.get("firmwareVersion"), body.get("serverUrl")));
    }

    /** 心跳上报（设备端，30s 间隔，90s 判离线） */
    @PostMapping("/report/heartbeat")
    public ApiResponse<Void> heartbeat(@RequestBody Map<String, String> body) {
        String deviceCode = body.get("deviceCode");
        if (deviceCode == null || !deviceService.exists(deviceCode)) {
            return ApiResponse.error(404, "设备不存在");
        }
        deviceService.heartbeat(deviceCode);
        return ApiResponse.ok(null);
    }

    /** 状态上报（固件版本等） */
    @PostMapping("/report/status")
    public ApiResponse<Void> reportStatus(@RequestBody Map<String, String> body) {
        String deviceCode = body.get("deviceCode");
        if (deviceCode == null || !deviceService.exists(deviceCode)) {
            return ApiResponse.error(404, "设备不存在");
        }
        deviceService.reportStatus(deviceCode, body.get("firmwareVersion"));
        return ApiResponse.ok(null);
    }

    /** 配置拉取（设备心跳时调用） */
    @PostMapping("/config/pull")
    public ApiResponse<Map<String, Object>> pullConfig(@RequestBody Map<String, String> body) {
        String deviceCode = body.get("deviceCode");
        if (deviceCode == null || !deviceService.exists(deviceCode)) {
            return ApiResponse.error(404, "设备不存在");
        }
        return ApiResponse.ok(deviceService.pullConfig(deviceCode));
    }

    /** 业务异常统一转 400（参数/状态非法），设备不存在单独 404 已在各端点前置处理 */
    private <T> ApiResponse<T> wrap(String deviceCode, java.util.function.Supplier<T> supplier) {
        try {
            return ApiResponse.ok(supplier.get());
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }
}
