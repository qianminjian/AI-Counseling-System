package com.mindsafe.api.controller;

import com.mindsafe.api.security.JwtTokenProvider;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.domain.entity.SysConfig;
import com.mindsafe.domain.entity.SysConfigHistory;
import com.mindsafe.service.config.SysConfigService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台配置管理端点（ADMIN-P1-01，M1 系统配置管理）
 * <p>
 * 配置注册表（SECRET 掩码 + HOT/RESTART + 变更留痕）；POST 仅 PLATFORM_SUPER_ADMIN
 * （SecurityConfig 端点级强制，reason 必填）。设计见 doing/83 §7.1。
 */
@RestController
@RequestMapping("/api/v1/platform/config")
public class PlatformConfigController {

    private final SysConfigService sysConfigService;

    public PlatformConfigController(SysConfigService sysConfigService) {
        this.sysConfigService = sysConfigService;
    }

    @GetMapping("/registry")
    public ApiResponse<List<SysConfig>> registry(@RequestParam(required = false) String domain) {
        return ApiResponse.ok(sysConfigService.listByDomain(domain));
    }

    @GetMapping("/{key}")
    public ApiResponse<SysConfig> get(@PathVariable String key) {
        return ApiResponse.ok(sysConfigService.get(key));
    }

    @PostMapping("/{key}")
    public ApiResponse<SysConfig> update(@PathVariable String key,
                                         @Valid @RequestBody ConfigUpdateRequest request) {
        return ApiResponse.ok(sysConfigService.update(key, request.value(), request.reason(), operatorName()));
    }

    @GetMapping("/{key}/history")
    public ApiResponse<List<SysConfigHistory>> history(
            @PathVariable String key,
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(sysConfigService.history(key, limit));
    }

    /** 操作人：平台账号显示名（业务 ADMIN 过渡期用认证主体名） */
    private String operatorName() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || auth.getName().isBlank()) {
            return "unknown";
        }
        return auth.getName();
    }

    public record ConfigUpdateRequest(
            @NotBlank(message = "value 不能为空") String value,
            @NotBlank(message = "reason 必填（变更原因留痕）") String reason) {
    }
}
