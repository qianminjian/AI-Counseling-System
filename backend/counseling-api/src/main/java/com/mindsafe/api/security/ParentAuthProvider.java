package com.mindsafe.api.security;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 家长端认证 Provider（ParentAuthController 注册/登录，AC-89-05）
 * <p>
 * 业务格式（userType=parent）：generateToken + generateRefreshToken，
 * 与 ParentAuthController 原内联签发一致。
 */
@Component
public class ParentAuthProvider implements AuthProvider {

    private final JwtTokenProvider jwtTokenProvider;

    public ParentAuthProvider(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    public String issueAccessToken(UUID userId, String userType, UUID tenantId) {
        return jwtTokenProvider.generateToken(userId, userType, tenantId);
    }

    @Override
    public String issueRefreshToken(UUID userId, String userType, UUID tenantId) {
        return jwtTokenProvider.generateRefreshToken(userId, userType, tenantId);
    }
}
