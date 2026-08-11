package com.mindsafe.api.security;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 平台端认证 Provider（PlatformAuthController 登录，AC-89-05）
 * <p>
 * PLATFORM_ 前缀格式（generatePlatformToken）；平台体系无刷新令牌语义。
 */
@Component
public class PlatformAuthProvider implements AuthProvider {

    private final JwtTokenProvider jwtTokenProvider;

    public PlatformAuthProvider(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    public String issueAccessToken(UUID userId, String userType, UUID tenantId) {
        return jwtTokenProvider.generatePlatformToken(userId, userType);
    }
}
