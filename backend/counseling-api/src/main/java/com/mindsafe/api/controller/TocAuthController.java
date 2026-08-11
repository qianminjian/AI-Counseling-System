package com.mindsafe.api.controller;

import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.domain.entity.TocFamilyAccount;
import com.mindsafe.service.toc.TocAuthService;
import com.mindsafe.api.security.TocAuthProvider;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * toC 家庭账号 API（doing/85 TOC-001，toC-AC-1）
 * <p>
 * 手机号验证码注册/登录（匿名，独立于校园体系）；/me 需登录态（标准 JWT，
 * userType=toc_parent）。token 由本层调用 JwtTokenProvider 签发（分层：service 不依赖 api）。
 */
@RestController
@RequestMapping("/api/v1/toc/auth")
public class TocAuthController {

    private final TocAuthService tocAuthService;
    private final TocAuthProvider tocAuthProvider;

    public TocAuthController(TocAuthService tocAuthService, TocAuthProvider tocAuthProvider) {
        this.tocAuthService = tocAuthService;
        this.tocAuthProvider = tocAuthProvider;
    }

    /** 发送验证码（注册/登录共用，匿名） */
    @PostMapping("/send-code")
    public ApiResponse<Map<String, Object>> sendCode(@RequestBody Map<String, String> body) {
        String phone = body.get("phone");
        try {
            return ApiResponse.ok(tocAuthService.sendCode(phone));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /** 注册：手机号 + 验证码 → 家庭账号 + token */
    @PostMapping("/register")
    public ApiResponse<Map<String, Object>> register(@RequestBody Map<String, String> body) {
        String phone = body.get("phone");
        String code = body.get("code");
        try {
            TocFamilyAccount account = tocAuthService.register(phone, code);
            return ApiResponse.ok(tocAuthProvider.buildSession(account));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /** 登录：手机号 + 验证码 → token */
    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        String phone = body.get("phone");
        String code = body.get("code");
        try {
            TocFamilyAccount account = tocAuthService.login(phone, code);
            return ApiResponse.ok(tocAuthProvider.buildSession(account));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /** 当前账号信息（登录态） */
    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me(Authentication auth) {
        TenantContext ctx = (TenantContext) auth.getDetails();
        TocFamilyAccount account = tocAuthService.getById(ctx.userId());
        if (account == null) {
            return ApiResponse.error(404, "账号不存在");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("familyAccountId", account.getFamilyAccountId());
        result.put("phone", maskPhone(account.getPhone()));
        result.put("status", account.getStatus());
        return ApiResponse.ok(result);
    }

    private static String maskPhone(String phone) {
        return phone == null || phone.length() < 7 ? phone
                : phone.substring(0, 3) + "****" + phone.substring(7);
    }
}
