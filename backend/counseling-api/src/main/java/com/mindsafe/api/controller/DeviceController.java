package com.mindsafe.api.controller;

import com.mindsafe.api.dto.device.BindDeviceRequest;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.service.device.DeviceService;
import com.mindsafe.service.device.DeviceVoiceprintService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

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
    private final DeviceVoiceprintService voiceprintService;

    public DeviceController(DeviceService deviceService, DeviceVoiceprintService voiceprintService) {
        this.deviceService = deviceService;
        this.voiceprintService = voiceprintService;
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
        return ApiResponse.ok(deviceService.createBindCode(deviceCode, operator));
    }

    /** 绑定（登录态，AC-84-10/11/12）：归属 + 验证码双因子 */
    @PostMapping("/{deviceCode}/bind")
    public ApiResponse<Map<String, Object>> bind(@PathVariable String deviceCode,
                                                 @RequestBody BindDeviceRequest request,
                                                 @RequestParam(required = false) String operator) {
        return ApiResponse.ok(deviceService.bind(deviceCode, request.getBindType(),
                request.getBindTargetId(), request.getStudentId(), request.getCode(), operator));
    }

    /** 解绑（登录态，reason 由审计层记录） */
    @PostMapping("/{deviceCode}/unbind")
    public ApiResponse<Map<String, Object>> unbind(@PathVariable String deviceCode,
                                                   @RequestParam(required = false) String operator) {
        return ApiResponse.ok(deviceService.unbind(deviceCode, operator));
    }

    /** 设备首次上线/回连注册（设备端上报；已存在设备需 X-Device-Token，AUDIT-DEEP-002 code-review P0-1） */
    @PostMapping("/report/online")
    public ApiResponse<Map<String, Object>> reportOnline(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Device-Token", required = false) String deviceToken) {
        return ApiResponse.ok(deviceService.reportOnline(
                body.get("deviceCode"), body.get("sn"), body.get("firmwareVersion"), body.get("serverUrl"),
                deviceToken));
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

    /** 配置拉取（设备心跳时调用；已绑定设备需 X-Device-Token，AUDIT-DEEP-002） */
    @PostMapping("/config/pull")
    public ApiResponse<Map<String, Object>> pullConfig(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Device-Token", required = false) String deviceToken) {
        String deviceCode = body.get("deviceCode");
        if (deviceCode == null || !deviceService.exists(deviceCode)) {
            return ApiResponse.error(404, "设备不存在");
        }
        return ApiResponse.ok(deviceService.pullConfig(deviceCode, deviceToken));
    }

    /** 老师租户级设备列表（CFG-008，按绑定归属过滤） */
    // BUG-T-09-01 修复（2026-08-12，UI-TEST-013）：参数改为可选 + 容错——
    // 此前 bindType/bindTargetId 必填且 bindTargetId 为 UUID 类型，前端挂载无参请求与
    // 非 UUID 输入（如数字）均触发参数异常落兜底 500。现缺失参数返回空列表，非法 UUID 400。
    @GetMapping("/list")
    public ApiResponse<List<Map<String, Object>>> listDevices(
            @RequestParam(required = false) String bindType,
            @RequestParam(required = false) String bindTargetId) {
        if (bindType == null || bindTargetId == null || bindType.isBlank() || bindTargetId.isBlank()) {
            return ApiResponse.ok(List.of());
        }
        return ApiResponse.ok(deviceService.listDevices(bindType.trim(), UUID.fromString(bindTargetId.trim())));
    }

    /** 发起声纹录入任务（CFG-006，AC-84-13，登录态） */
    @PostMapping("/{deviceCode}/voiceprint/tasks")
    public ApiResponse<Map<String, Object>> createVoiceprintTask(
            @PathVariable String deviceCode,
            @RequestBody Map<String, String> body,
            @RequestParam(required = false) String operator) {
        String studentId = body.get("studentId");
        if (studentId == null || studentId.isBlank()) {
            return ApiResponse.error(400, "studentId 缺失");
        }
        if (!deviceService.exists(deviceCode)) {
            return ApiResponse.error(404, "设备不存在");
        }
        return ApiResponse.ok(voiceprintService.createTask(deviceCode, studentId, operator));
    }

    /** 轮询声纹录入任务（CFG-006，AC-84-14） */
    @GetMapping("/{deviceCode}/voiceprint/tasks/{taskId}")
    public ApiResponse<Map<String, Object>> getVoiceprintTask(
            @PathVariable String deviceCode,
            @PathVariable String taskId) {
        Map<String, Object> task = voiceprintService.getTask(taskId);
        if (task == null || !deviceCode.equals(task.get("deviceCode"))) {
            return ApiResponse.error(404, "任务不存在");
        }
        return ApiResponse.ok(task);
    }

    /** 设备端采集进度上报（CFG-006，AC-84-13，匿名白名单） */
    @PostMapping("/report/voiceprint")
    public ApiResponse<Map<String, Object>> reportVoiceprintPhase(@RequestBody Map<String, String> body) {
        String taskId = body.get("taskId");
        String phase = body.get("phase");
        if (taskId == null || phase == null) {
            return ApiResponse.error(400, "taskId/phase 缺失");
        }
        Map<String, Object> task = voiceprintService.reportPhase(taskId, phase, body.get("deviceCode"));
        if (task == null) {
            return ApiResponse.error(404, "任务不存在");
        }
        return ApiResponse.ok(task);
    }

    /** 固件升级受理（CFG-008 M13，AC-84-20，登录态，X-Confirm 语义） */
    @PostMapping("/{deviceCode}/ota")
    public ApiResponse<Map<String, Object>> ota(@PathVariable String deviceCode,
                                                @RequestParam(required = false) String operator) {
        return ApiResponse.ok(deviceService.ota(deviceCode, operator));
    }

    /** 远程重启受理（CFG-008 M13，登录态） */
    @PostMapping("/{deviceCode}/reboot")
    public ApiResponse<Map<String, Object>> reboot(@PathVariable String deviceCode,
                                                   @RequestParam(required = false) String operator) {
        return ApiResponse.ok(deviceService.reboot(deviceCode, operator));
    }

    /** 恢复出厂（CFG-008 M13，AC-84-19：解绑+状态回 UNACTIVATED，登录态） */
    @PostMapping("/{deviceCode}/factory-reset")
    public ApiResponse<Map<String, Object>> factoryReset(@PathVariable String deviceCode,
                                                         @RequestParam(required = false) String operator) {
        return ApiResponse.ok(deviceService.factoryReset(deviceCode, operator));
    }

}
