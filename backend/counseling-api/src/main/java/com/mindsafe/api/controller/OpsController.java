package com.mindsafe.api.controller;

import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.domain.entity.AuditLog;
import com.mindsafe.domain.entity.ServiceHealthSnapshot;
import com.mindsafe.service.monitoring.OpsService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 运维域端点（ADMIN-P0-05/06/07，M2 服务拓扑/告警只读 + M6 审计跨租户查询）
 * <p>
 * 仅平台角色可访问（SecurityConfig：PLATFORM_SUPER_ADMIN/PLATFORM_OPS_ADMIN/PLATFORM_AUDIT）。
 * 设计见 doing/83 后台管理端 §7.2。
 */
@RestController
@RequestMapping("/api/v1/ops")
public class OpsController {

    private final OpsService opsService;

    public OpsController(OpsService opsService) {
        this.opsService = opsService;
    }

    @GetMapping("/services/status")
    public ApiResponse<Map<String, String>> servicesStatus() {
        return ApiResponse.ok(opsService.servicesStatus());
    }

    @GetMapping("/services/health-history")
    public ApiResponse<List<ServiceHealthSnapshot>> healthHistory(
            @RequestParam(required = false) String service,
            @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.ok(opsService.healthHistory(service, limit));
    }

    @GetMapping("/alerts")
    public ApiResponse<List<Map<String, Object>>> alerts() {
        return ApiResponse.ok(opsService.activeAlerts());
    }

    @GetMapping("/audit-logs")
    public ApiResponse<List<AuditLog>> auditLogs(
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endTime,
            @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.ok(opsService.auditLogs(tenantId, action, startTime, endTime, limit));
    }
}
