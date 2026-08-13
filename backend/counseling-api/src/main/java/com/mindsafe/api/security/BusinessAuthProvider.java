package com.mindsafe.api.security;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 业务端认证 Provider（AuthController 登录/trial/pin/voice 四类登录，AC-89-05）
 * <p>
 * 业务格式（无前缀）：generateToken + generateRefreshToken，与 AuthController 原内联签发一致。
 */
@Component
public class BusinessAuthProvider implements AuthProvider {

    private final JwtTokenProvider jwtTokenProvider;

    public BusinessAuthProvider(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    public String issueAccessToken(UUID userId, String userType, UUID tenantId) {
        return jwtTokenProvider.generateToken(userId, userType, tenantId);
    }

    /** 家长登录签发 PARENT_REPORT 类型（BACK-008：家长域接口强制该类型，ACCESS 会被拒） */
    public String issueParentAccessToken(UUID parentId, UUID tenantId) {
        return jwtTokenProvider.generateParentLoginToken(parentId, tenantId);
    }

    @Override
    public String issueRefreshToken(UUID userId, String userType, UUID tenantId) {
        return jwtTokenProvider.generateRefreshToken(userId, userType, tenantId);
    }
}
