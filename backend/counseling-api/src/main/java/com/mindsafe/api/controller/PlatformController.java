package com.mindsafe.api.controller;

import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.School;
import com.mindsafe.service.platform.PlatformService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 平台管理后台 API（SaaS 运营者视角）
 * <p>
 * 跨租户统计：学校数 / 学生数 / 会话量 / 活跃度 / 风险概览
 * <p>
 * C3（2026-08-05）：聚合查询下沉 PlatformService，本类仅保留 HTTP 编排。
 */
@RestController
@RequestMapping("/api/v1/platform")
public class PlatformController {

    private final PlatformService platformService;

    public PlatformController(PlatformService platformService) {
        this.platformService = platformService;
    }

    /** 平台总览（跨租户聚合） */
    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> getOverview() {
        return ApiResponse.ok(platformService.overview());
    }

    /** 租户列表（含各校学生/教师数） */
    @GetMapping("/tenant-stats")
    public ApiResponse<List<Map<String, Object>>> getTenants() {
        return ApiResponse.ok(platformService.tenantStats());
    }

    /** 单租户详情（学校列表 + 近 7 天会话趋势） */
    @GetMapping("/tenants/{tenantId}")
    public ApiResponse<Map<String, Object>> getTenantDetail(@PathVariable UUID tenantId) {
        Map<String, Object> detail = platformService.tenantDetail(tenantId);
        if (detail == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "租户不存在");
        }
        return ApiResponse.ok(detail);
    }

    /** 学校列表（跨租户） */
    @GetMapping("/schools")
    public ApiResponse<List<School>> getSchools() {
        return ApiResponse.ok(platformService.schools());
    }
}
