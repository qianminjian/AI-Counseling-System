package com.mindsafe.api.controller;

import com.mindsafe.api.security.PlatformAuthProvider;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.PlatformAdmin;
import com.mindsafe.service.platform.PlatformAdminService;
import com.mindsafe.service.platform.PlatformLoginGuard;
import jakarta.servlet.http.HttpServletRequest;
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
    private final PlatformAuthProvider platformAuthProvider;
    private final PlatformLoginGuard loginGuard;

    public PlatformAuthController(PlatformAdminService platformAdminService,
                                  PlatformAuthProvider platformAuthProvider,
                                  PlatformLoginGuard loginGuard) {
        this.platformAdminService = platformAdminService;
        this.platformAuthProvider = platformAuthProvider;
        this.loginGuard = loginGuard;
    }

    @PostMapping("/login")
    public ApiResponse<PlatformLoginResponse> login(@Valid @RequestBody PlatformLoginRequest request,
                                                    HttpServletRequest httpRequest) {
        String clientIp = clientIp(httpRequest);
        // 防爆破（P0 backlog M3）：锁定期间拒绝登录
        if (loginGuard.isLocked(clientIp)) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "失败次数过多，请 15 分钟后再试");
        }
        try {
            PlatformAdmin admin = platformAdminService.login(request.username(), request.password());
            loginGuard.recordSuccess(clientIp);
            // AC-89-05：平台 token 经 PlatformAuthProvider 统一签发（PLATFORM_ 格式不变）
            String token = platformAuthProvider.issueAccessToken(admin.getAdminId(), admin.getRole(), null);
            return ApiResponse.ok(new PlatformLoginResponse(token, admin.getRole(), admin.getDisplayName()));
        } catch (BizException e) {
            loginGuard.recordFailure(clientIp);
            throw e;
        }
    }

    /** 客户端 IP（code-review H3：XFF 取尾元素 = nginx $proxy_add_x_forwarded_for 追加的
     *  $remote_addr，不可伪造；首元素客户端可控） */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] parts = forwarded.split(",");
            return parts[parts.length - 1].trim();
        }
        return request.getRemoteAddr();
    }

    public record PlatformLoginRequest(
            @NotBlank(message = "用户名不能为空") String username,
            @NotBlank(message = "密码不能为空") String password) {
    }

    public record PlatformLoginResponse(String token, String role, String displayName) {
    }
}
