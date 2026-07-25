package com.mindsafe.api.auth;

import java.util.UUID;

/**
 * 统一身份载体（认证策略的输出）
 * <p>
 * 所有认证策略（试用/学校/微信/手机号）认证成功后返回此对象，
 * 由 Controller 层签发 JWT。
 */
public record AuthenticatedUser(
        UUID userId,
        String userType,
        UUID tenantId,
        String pseudonym,
        boolean mustChangePassword
) {
}
