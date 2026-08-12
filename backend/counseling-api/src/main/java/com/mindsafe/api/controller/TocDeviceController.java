package com.mindsafe.api.controller;

import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.api.security.SecuritySupport;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.service.toc.TocDeviceService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * toC 设备绑定 API（doing/85 TOC-003，toC-AC-3；联动 doing/84 CFG-010）
 * <p>
 * 家庭账号绑定无屏终端（登录态 ROLE_TOC_PARENT）：发起验证码 → 双因子绑定
 * （bind_type=FAMILY）→ 家庭设备列表/解绑。复用 84 设备域全链路。
 */
@RestController
@RequestMapping("/api/v1/toc/devices")
public class TocDeviceController {

    private final TocDeviceService tocDeviceService;

    public TocDeviceController(TocDeviceService tocDeviceService) {
        this.tocDeviceService = tocDeviceService;
    }

    /** 发起绑定验证码会话（触发设备语音播报） */
    @PostMapping("/{deviceCode}/bind-code")
    public ApiResponse<Map<String, Object>> createBindCode(Authentication auth, @PathVariable String deviceCode) {
        // AD-007：异常统一由 GlobalExceptionHandler 转 400（IllegalArgumentException）
        return ApiResponse.ok(tocDeviceService.createBindCode(deviceCode, operator(auth)));
    }

    /** 家庭绑定：body = {code, profileId?} */
    @PostMapping("/{deviceCode}/bind")
    public ApiResponse<Map<String, Object>> bind(Authentication auth, @PathVariable String deviceCode,
                                                 @RequestBody Map<String, Object> body) {
        UUID profileId = body.get("profileId") == null ? null : UUID.fromString(String.valueOf(body.get("profileId")));
        return ApiResponse.ok(tocDeviceService.bind(accountId(auth), deviceCode, profileId,
                String.valueOf(body.get("code")), operator(auth)));
    }

    /** 解绑 */
    @PostMapping("/{deviceCode}/unbind")
    public ApiResponse<Map<String, Object>> unbind(Authentication auth, @PathVariable String deviceCode) {
        return ApiResponse.ok(tocDeviceService.unbind(accountId(auth), deviceCode, operator(auth)));
    }

    /** 家庭设备列表 */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(Authentication auth) {
        return ApiResponse.ok(tocDeviceService.listDevices(accountId(auth)));
    }

    private UUID accountId(Authentication auth) {
        TenantContext ctx = SecuritySupport.requireContext(auth);
        return ctx.userId();
    }

    private String operator(Authentication auth) {
        TenantContext ctx = SecuritySupport.requireContext(auth);
        return ctx.userId() == null ? null : ctx.userId().toString();
    }
}
