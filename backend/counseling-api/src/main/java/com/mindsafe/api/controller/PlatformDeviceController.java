package com.mindsafe.api.controller;

import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.service.device.PlatformDeviceService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 无屏终端平台管理 API（CFG-008 admin-web M13，doing/84 §六.2 平台管理域）
 * <p>
 * 跨租户设备管理（super_admin/ops_admin，PLATFORM_ token）：设备列表（状态/归属
 * 筛选）、设备详情（含绑定历史）、二维码批量签发（印刷包留痕）、批量操作受理。
 */
@RestController
@RequestMapping("/api/v1/platform/devices")
public class PlatformDeviceController {

    private final PlatformDeviceService platformDeviceService;

    public PlatformDeviceController(PlatformDeviceService platformDeviceService) {
        this.platformDeviceService = platformDeviceService;
    }

    /** 跨租户设备列表（AC-84-18）：?status=ONLINE_BOUND&bindTargetId=xxx */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> listDevices(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID bindTargetId) {
        return ApiResponse.ok(platformDeviceService.listDevices(status, bindTargetId));
    }

    /** 设备详情（含绑定历史） */
    @GetMapping("/{deviceId}")
    public ApiResponse<Map<String, Object>> getDeviceDetail(@PathVariable UUID deviceId) {
        Map<String, Object> detail = platformDeviceService.getDeviceDetail(deviceId);
        if (detail == null) {
            return ApiResponse.error(404, "设备不存在");
        }
        return ApiResponse.ok(detail);
    }

    /** 二维码批量签发（CFG-005 落地）：body = {deviceCodes: [...], issuedBy: "admin"} */
    @PostMapping("/export-qr")
    public ApiResponse<Map<String, Object>> exportQr(@RequestBody Map<String, Object> body) {
        List<String> deviceCodes = castStringList(body.get("deviceCodes"));
        if (deviceCodes == null || deviceCodes.isEmpty()) {
            return ApiResponse.error(400, "deviceCodes 缺失");
        }
        String issuedBy = body.get("issuedBy") == null ? null : String.valueOf(body.get("issuedBy"));
        return ApiResponse.ok(platformDeviceService.exportQr(deviceCodes, issuedBy));
    }

    /** 批量操作受理：body = {deviceCodes: [...], action: "ota|reboot|factory-reset", operator: "admin"} */
    @PostMapping("/batch")
    public ApiResponse<Map<String, Object>> batchOperation(@RequestBody Map<String, Object> body) {
        List<String> deviceCodes = castStringList(body.get("deviceCodes"));
        if (deviceCodes == null || deviceCodes.isEmpty()) {
            return ApiResponse.error(400, "deviceCodes 缺失");
        }
        String action = body.get("action") == null ? null : String.valueOf(body.get("action"));
        String operator = body.get("operator") == null ? null : String.valueOf(body.get("operator"));
        try {
            return ApiResponse.ok(platformDeviceService.batchOperation(deviceCodes, action, operator));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> castStringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return null;
    }
}
