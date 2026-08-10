package com.mindsafe.api.controller;

import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.service.device.DevicePreferenceService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * toC 远程管理 API（doing/85 TOC-006，toC-AC-6）
 * <p>
 * 家长设置设备偏好（音量/音色/对话偏好），按家庭账号隔离；
 * 设备端拉取配置（/device/config/pull）时下发（软件侧生效，固件执行 NST-HW-02 二期）。
 */
@RestController
@RequestMapping("/api/v1/toc/devices/{deviceCode}/preferences")
public class TocPreferenceController {

    private final DevicePreferenceService preferenceService;

    public TocPreferenceController(DevicePreferenceService preferenceService) {
        this.preferenceService = preferenceService;
    }

    /** 查询设备偏好 */
    @GetMapping
    public ApiResponse<Map<String, Object>> get(Authentication auth, @PathVariable String deviceCode) {
        Map<String, Object> prefs = preferenceService.getPreferences(accountId(auth), deviceCode);
        if (prefs == null) {
            return ApiResponse.error(404, "该设备无偏好设置");
        }
        return ApiResponse.ok(prefs);
    }

    /** 设置设备偏好：body = {volume?, voicePersona?, dialoguePref?} */
    @PutMapping
    public ApiResponse<Map<String, Object>> set(Authentication auth, @PathVariable String deviceCode,
                                                @RequestBody Map<String, Object> body) {
        Integer volume = body.get("volume") == null ? null : Integer.parseInt(String.valueOf(body.get("volume")));
        String voicePersona = body.get("voicePersona") == null ? null : String.valueOf(body.get("voicePersona"));
        String dialoguePref = body.get("dialoguePref") == null ? null : String.valueOf(body.get("dialoguePref"));
        try {
            return ApiResponse.ok(preferenceService.setPreferences(
                    accountId(auth), deviceCode, volume, voicePersona, dialoguePref));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    private UUID accountId(Authentication auth) {
        TenantContext ctx = (TenantContext) auth.getDetails();
        return ctx.userId();
    }
}
