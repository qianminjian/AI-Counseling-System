package com.mindsafe.api.controller;

import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.service.safety.SosEventService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * SOS 事件上报端点（P0-2 审计修复，design/36 §五 API 契约：POST /api/v1/sos/events）。
 * <p>
 * 前端 fire-and-forget 调用（toolboxApi.reportSosEvent），服务端落 S2 风险事件进教师预警队列。
 */
@RestController
@RequestMapping("/api/v1/sos")
public class SosController {

    private final SosEventService sosEventService;

    public SosController(SosEventService sosEventService) {
        this.sosEventService = sosEventService;
    }

    /**
     * SOS 打开事件上报（design/36 M2：网络可用时 1min 内产生 S2 事件）。
     * 5 分钟去重窗口防儿童重复点击刷屏。
     */
    @PostMapping("/events")
    public ApiResponse<Map<String, Object>> reportSosEvent(Authentication auth) {
        TenantContext ctx = (TenantContext) auth.getDetails();
        SosEventService.SosResult result = sosEventService.recordSosEvent(ctx.tenantId(), ctx.userId());
        return ApiResponse.ok(Map.of(
                "deduplicated", result.deduplicated(),
                "riskEventId", result.riskEventId() != null ? result.riskEventId().toString() : ""));
    }
}
