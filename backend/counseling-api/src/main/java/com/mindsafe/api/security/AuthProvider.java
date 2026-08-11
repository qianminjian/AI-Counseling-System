package com.mindsafe.api.security;

import java.util.UUID;

/**
 * 认证 Provider 统一接缝（doing/89 N-001 AC-89-05，2026-08-11）
 * <p>
 * 登录成功后 token 签发的统一入口：业务/平台/toc/家长四体系各自实现，
 * 格式保持兼容（业务无前缀 / PLATFORM_ 前缀 / toc 平台级 / 家长 parent 类型）。
 * 特殊凭证（parent_report 链接 token、声纹凭证、企微 OAuth）不属于"登录成功"
 * 语义，保持独立签发（SPEC 边界）。
 */
public interface AuthProvider {

    /** 登录成功后签发访问令牌（格式各体系保持兼容） */
    String issueAccessToken(UUID userId, String userType, UUID tenantId);

    /** 刷新令牌（无刷新语义体系返回 null） */
    default String issueRefreshToken(UUID userId, String userType, UUID tenantId) {
        return null;
    }
}
