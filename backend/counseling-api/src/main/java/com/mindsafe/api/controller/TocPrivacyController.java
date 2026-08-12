package com.mindsafe.api.controller;

import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.api.security.SecuritySupport;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.service.toc.TocPrivacyService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * toC 隐私控制 API（doing/85 TOC-007，toC-AC-7）
 * <p>
 * 数据查看/删除（不可逆）：删除 = 解绑全部设备 + 删全部档案 + 账号 DISABLED。
 */
@RestController
@RequestMapping("/api/v1/toc/privacy")
public class TocPrivacyController {

    private final TocPrivacyService tocPrivacyService;

    public TocPrivacyController(TocPrivacyService tocPrivacyService) {
        this.tocPrivacyService = tocPrivacyService;
    }

    /** 数据清单预览 */
    @GetMapping
    public ApiResponse<Map<String, Object>> overview(Authentication auth) {
        try {
            return ApiResponse.ok(tocPrivacyService.getDataOverview(accountId(auth)));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /** 删除全部家庭数据（不可逆，X-Confirm 二次确认语义） */
    @DeleteMapping("/data")
    public ApiResponse<Map<String, Object>> deleteAll(Authentication auth,
                                                      @RequestHeader(value = "X-Confirm", required = false) String confirm) {
        if (!"CONFIRM".equals(confirm)) {
            return ApiResponse.error(400, "需要 X-Confirm: CONFIRM 二次确认");
        }
        try {
            return ApiResponse.ok(tocPrivacyService.deleteAllData(accountId(auth)));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    private UUID accountId(Authentication auth) {
        TenantContext ctx = SecuritySupport.requireContext(auth);
        return ctx.userId();
    }
}
