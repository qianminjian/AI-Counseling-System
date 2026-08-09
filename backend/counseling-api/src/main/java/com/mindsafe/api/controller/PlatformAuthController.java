package com.mindsafe.api.controller;

import com.mindsafe.api.security.JwtTokenProvider;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.domain.entity.PlatformAdmin;
import com.mindsafe.service.platform.PlatformAdminService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台管理员认证（ADMIN-P0-02，M6 平台基础，R-1/R-8）
 * <p>
 * 独立登录端点 /api/v1/platform/auth/login：独立 platform_admin 表 + PLATFORM_
 * token 前缀，与业务登录态隔离（DEC-007）。设计见 doing/83 §7.6。
 */
@RestController
@RequestMapping("/api/v1/platform/auth")
public class PlatformAuthController {

    private final PlatformAdminService platformAdminService;
    private final JwtTokenProvider jwtTokenProvider;

    public PlatformAuthController(PlatformAdminService platformAdminService,
                                  JwtTokenProvider jwtTokenProvider) {
        this.platformAdminService = platformAdminService;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @PostMapping("/login")
    public ApiResponse<PlatformLoginResponse> login(@Valid @RequestBody PlatformLoginRequest request) {
        PlatformAdmin admin = platformAdminService.login(request.username(), request.password());
        String token = jwtTokenProvider.generatePlatformToken(admin.getAdminId(), admin.getRole());
        return ApiResponse.ok(new PlatformLoginResponse(token, admin.getRole(), admin.getDisplayName()));
    }

    public record PlatformLoginRequest(
            @NotBlank(message = "用户名不能为空") String username,
            @NotBlank(message = "密码不能为空") String password) {
    }

    public record PlatformLoginResponse(String token, String role, String displayName) {
    }
}
