package com.mindsafe.api.controller;

import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.api.security.SecuritySupport;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.domain.entity.TocChildProfile;
import com.mindsafe.service.toc.TocFamilyService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * toC 孩子档案 API（doing/85 TOC-002，toC-AC-2）
 * <p>
 * 一账号多孩档案 CRUD（登录态 ROLE_TOC_PARENT），数据按 familyAccountId 隔离
 * （familyAccountId 取自 token 上下文，客户端不可指定）。
 */
@RestController
@RequestMapping("/api/v1/toc/profiles")
public class TocProfileController {

    private final TocFamilyService tocFamilyService;

    public TocProfileController(TocFamilyService tocFamilyService) {
        this.tocFamilyService = tocFamilyService;
    }

    /** 档案列表（本人账号） */
    @GetMapping
    public ApiResponse<List<TocChildProfile>> list(Authentication auth) {
        return ApiResponse.ok(tocFamilyService.listProfiles(accountId(auth)));
    }

    /** 创建档案 */
    @PostMapping
    public ApiResponse<TocChildProfile> create(Authentication auth, @RequestBody Map<String, Object> body) {
        try {
            return ApiResponse.ok(tocFamilyService.createProfile(accountId(auth), body));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /** 更新档案（归属校验） */
    @PutMapping("/{profileId}")
    public ApiResponse<TocChildProfile> update(Authentication auth, @PathVariable UUID profileId,
                                               @RequestBody Map<String, Object> body) {
        try {
            return ApiResponse.ok(tocFamilyService.updateProfile(accountId(auth), profileId, body));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /** 删除档案（归属校验） */
    @DeleteMapping("/{profileId}")
    public ApiResponse<Void> delete(Authentication auth, @PathVariable UUID profileId) {
        try {
            tocFamilyService.deleteProfile(accountId(auth), profileId);
            return ApiResponse.ok(null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    private UUID accountId(Authentication auth) {
        TenantContext ctx = SecuritySupport.requireContext(auth);
        return ctx.userId();
    }
}
