package com.mindsafe.api.security;

import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import org.springframework.security.core.Authentication;

import java.util.UUID;

/**
 * 认证上下文提取统一单点（S-011，doing/93）。
 * <p>
 * 此前 Controller 层三种写法并存：57 处无校验直接强转（null/非 TenantContext → 500 而非 401）、
 * 21 处取 principal、少数 instanceof 校验；新 Controller 无所适从。统一经本组件提取：
 * 缺失/类型不符 → UNAUTHORIZED（显式校验，与 R-018 议决的 details 通道语义一致）。
 */
public final class SecuritySupport {

    private SecuritySupport() {
    }

    /** 从 Authentication.details 提取租户上下文（显式校验；缺失/类型不符 → UNAUTHORIZED） */
    public static TenantContext requireContext(Authentication auth) {
        if (auth == null || !(auth.getDetails() instanceof TenantContext ctx)) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return ctx;
    }

    /** 提取当前用户 ID（details 通道统一取 userId） */
    public static UUID requireUserId(Authentication auth) {
        return requireContext(auth).userId();
    }
}
