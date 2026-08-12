package com.mindsafe.api.controller;

import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.AuditLog;
import com.mindsafe.service.admin.AdminService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 审计日志查询（doing/92 R-002：审计日志为全局资源，从 invite-codes 子资源迁出独立端点）。
 * <p>
 * 权限：/api/v1/admin/** 由 SecurityConfig 统一要求 ADMIN 角色，本端点自动受保护。
 * 旧路径 /api/v1/admin/invite-codes/audit-logs 由 AdminController 保留为兼容别名（deprecated）。
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AuditLogController {

    private final AdminService adminService;

    public AuditLogController(AdminService adminService) {
        this.adminService = adminService;
    }

    /** 查询审计日志（admin 专用，最近 200 条） */
    @GetMapping("/audit-logs")
    public ApiResponse<List<AuditLog>> getAuditLogs(
            Authentication auth,
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "200") int limit) {
        TenantContext ctx = extractContext(auth);
        return ApiResponse.ok(adminService.getAuditLogs(ctx.tenantId(), action, limit));
    }

    /** 从 Authentication.details 提取租户上下文（R-018 议决：details 为 controller 显式参数通道） */
    private TenantContext extractContext(Authentication authentication) {
        if (authentication == null || !(authentication.getDetails() instanceof TenantContext ctx)) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return ctx;
    }
}
