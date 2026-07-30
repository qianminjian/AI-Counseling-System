package com.mindsafe.api.controller;

import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.domain.entity.Tenant;
import com.mindsafe.service.audit.AuditLogService;
import com.mindsafe.service.tenant.TenantProvisioningService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 租户管理 API（BIZ-001：多租户生产化）
 * <p>
 * 功能：一键开通 / 暂停恢复 / 健康检查 / 列表
 * 权限：仅平台管理员（super_admin）
 */
@RestController
@RequestMapping("/api/v1/platform/tenants")
public class AdminTenantController {

    private final TenantProvisioningService provisioningService;
    private final AuditLogService auditLogService;

    /** 临时密码字符集（排除易混淆字符 0/O/1/l/I）与长度。 */
    private static final String PWD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789@#%$";
    private static final int TEMP_PASSWORD_LENGTH = 14;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public AdminTenantController(TenantProvisioningService provisioningService,
                                 AuditLogService auditLogService) {
        this.provisioningService = provisioningService;
        this.auditLogService = auditLogService;
    }

    /** 生成强随机临时密码（首登强制修改），避免硬编码弱口令。 */
    private static String generateTempPassword() {
        StringBuilder sb = new StringBuilder(TEMP_PASSWORD_LENGTH);
        for (int i = 0; i < TEMP_PASSWORD_LENGTH; i++) {
            sb.append(PWD_ALPHABET.charAt(SECURE_RANDOM.nextInt(PWD_ALPHABET.length())));
        }
        return sb.toString();
    }

    /** 一键开通租户 */
    @PostMapping("/provision")
    public ApiResponse<Map<String, Object>> provision(
            @RequestBody Map<String, String> body, Authentication auth) {
        TenantContext ctx = (TenantContext) auth.getDetails();
        String tenantCode = body.get("tenantCode");
        String tenantName = body.get("tenantName");
        String adminPhone = body.get("adminPhone");
        String adminName = body.get("adminName");
        // 默认生成强随机临时密码（首登强制修改）；仅当调用方显式传入时才沿用。禁用硬编码默认弱口令。
        String tempPassword = body.get("tempPassword");
        if (tempPassword == null || tempPassword.isBlank()) {
            tempPassword = generateTempPassword();
        }

        if (tenantCode == null || tenantName == null || adminPhone == null) {
            return ApiResponse.ok(Map.of("error", "tenantCode/tenantName/adminPhone 为必填项"));
        }

        TenantProvisioningService.ProvisionResult result =
                provisioningService.provisionTenant(tenantCode, tenantName, adminPhone, adminName, tempPassword);

        auditLogService.log(ctx.tenantId(), ctx.userId(), "TENANT_PROVISION", "tenant", result.tenantId(),
                "code=" + tenantCode + ", name=" + tenantName);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("tenantId", result.tenantId());
        response.put("schoolId", result.schoolId());
        response.put("adminUserId", result.adminUserId());
        response.put("message", "租户开通成功，管理员初始密码: " + tempPassword);
        return ApiResponse.ok(response);
    }

    /** 暂停租户 */
    @PostMapping("/{tenantId}/suspend")
    public ApiResponse<Void> suspend(@PathVariable UUID tenantId,
                                     @RequestBody(required = false) Map<String, String> body,
                                     Authentication auth) {
        TenantContext ctx = (TenantContext) auth.getDetails();
        String reason = body != null ? body.get("reason") : null;
        provisioningService.suspendTenant(tenantId, reason);
        auditLogService.log(ctx.tenantId(), ctx.userId(), "TENANT_SUSPEND", "tenant", tenantId, reason);
        return ApiResponse.ok(null);
    }

    /** 恢复租户 */
    @PostMapping("/{tenantId}/resume")
    public ApiResponse<Void> resume(@PathVariable UUID tenantId, Authentication auth) {
        TenantContext ctx = (TenantContext) auth.getDetails();
        provisioningService.resumeTenant(tenantId);
        auditLogService.log(ctx.tenantId(), ctx.userId(), "TENANT_RESUME", "tenant", tenantId, null);
        return ApiResponse.ok(null);
    }

    /** 租户健康检查 */
    @GetMapping("/{tenantId}/health")
    public ApiResponse<Map<String, Object>> health(@PathVariable UUID tenantId) {
        return ApiResponse.ok(provisioningService.healthCheck(tenantId));
    }

    /** 租户列表 */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        List<Tenant> tenants = provisioningService.listTenants();
        List<Map<String, Object>> result = tenants.stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tenantId", t.getTenantId());
            m.put("tenantCode", t.getTenantCode());
            m.put("tenantName", t.getTenantName());
            m.put("status", t.getStatus());
            m.put("dataRegion", t.getDataRegion());
            m.put("createdAt", t.getCreatedAt());
            return m;
        }).collect(Collectors.toList());
        return ApiResponse.ok(result);
    }
}
