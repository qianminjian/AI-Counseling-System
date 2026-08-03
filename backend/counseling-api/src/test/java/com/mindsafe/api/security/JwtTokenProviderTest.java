package com.mindsafe.api.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JwtTokenProvider 单元测试（审计 P1-17：auth 全链路补测）
 * 覆盖：双 Token 类型隔离、声纹凭证、家长报告 token、篡改/过期拒绝、密钥强度门禁
 */
class JwtTokenProviderTest {

    private static final String SECRET = "unit-test-secret-key-at-least-32-characters-long-abcdef";
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID TENANT_ID = UUID.randomUUID();

    private final JwtTokenProvider provider = new JwtTokenProvider(
            SECRET, 7200000L, 604800000L, 7776000000L, 604800000L, "dev");

    // ===== Token 类型隔离（防 refresh token 调 API / 学生 token 调家长接口） =====

    @Test
    @DisplayName("access token 往返：claims 完整 + 类型判定互斥")
    void accessTokenRoundTrip() {
        String token = provider.generateToken(USER_ID, "student", TENANT_ID);

        Claims claims = provider.parseToken(token);
        assertEquals(USER_ID.toString(), claims.getSubject());
        assertEquals("student", claims.get("userType", String.class));
        assertEquals(TENANT_ID.toString(), claims.get("tenantId", String.class));
        assertEquals("access", claims.get("tokenType", String.class));

        assertTrue(provider.isAccessToken(token));
        assertFalse(provider.isRefreshToken(token));
        assertFalse(provider.isVoiceCredential(token));
        assertFalse(provider.isParentReportToken(token));

        assertEquals(USER_ID, provider.getUserId(token));
        assertEquals("student", provider.getUserType(token));
        assertEquals(TENANT_ID, provider.getTenantId(token));
        assertTrue(provider.validateToken(token));
        assertTrue(provider.getRemainingMs(token) > 0);
    }

    @Test
    @DisplayName("refresh token 不能当 access 用")
    void refreshTokenIsNotAccess() {
        String token = provider.generateRefreshToken(USER_ID, "teacher", TENANT_ID);
        assertTrue(provider.isRefreshToken(token));
        assertFalse(provider.isAccessToken(token));
        assertTrue(provider.validateToken(token));
    }

    @Test
    @DisplayName("声纹设备凭证独立类型")
    void voiceCredentialType() {
        String token = provider.generateVoiceCredential(USER_ID, "student", TENANT_ID);
        assertTrue(provider.isVoiceCredential(token));
        assertFalse(provider.isAccessToken(token));
    }

    @Test
    @DisplayName("家长报告 token 独立类型 + userType 固定 parent（SEC-006）")
    void parentReportTokenType() {
        String token = provider.generateParentReportToken(USER_ID, TENANT_ID);
        assertTrue(provider.isParentReportToken(token));
        assertFalse(provider.isAccessToken(token));
        assertEquals("parent", provider.getUserType(token));
    }

    // ===== 篡改 / 过期 / 垃圾输入拒绝 =====

    @Test
    @DisplayName("篡改的 token 校验失败")
    void tamperedTokenRejected() {
        String token = provider.generateToken(USER_ID, "student", TENANT_ID);
        String tampered = token.substring(0, token.length() - 4) + "AAAA";
        assertFalse(provider.validateToken(tampered));
        assertFalse(provider.isAccessToken(tampered));
    }

    @Test
    @DisplayName("过期 token 校验失败")
    void expiredTokenRejected() throws InterruptedException {
        JwtTokenProvider shortLivedProvider = new JwtTokenProvider(
                SECRET, 50L, 604800000L, 7776000000L, 604800000L, "dev");
        String token = shortLivedProvider.generateToken(USER_ID, "student", TENANT_ID);
        Thread.sleep(100);
        assertFalse(shortLivedProvider.validateToken(token));
        assertFalse(shortLivedProvider.isAccessToken(token));
    }

    @Test
    @DisplayName("其他密钥签发的 token 校验失败")
    void foreignSignedTokenRejected() {
        JwtTokenProvider other = new JwtTokenProvider(
                "another-secret-key-at-least-32-characters-long-xyz",
                7200000L, 604800000L, 7776000000L, 604800000L, "dev");
        String token = other.generateToken(USER_ID, "student", TENANT_ID);
        assertFalse(provider.validateToken(token));
    }

    @Test
    @DisplayName("垃圾字符串不抛异常、返回 false")
    void garbageTokenRejected() {
        assertFalse(provider.validateToken("not-a-jwt"));
        assertFalse(provider.isAccessToken("not-a-jwt"));
    }

    // ===== 密钥强度门禁 =====

    @Test
    @DisplayName("密钥不足 32 字符 → 启动失败")
    void shortSecretRejected() {
        assertThrows(IllegalStateException.class, () -> new JwtTokenProvider(
                "too-short", 7200000L, 604800000L, 7776000000L, 604800000L, "dev"));
    }

    @Test
    @DisplayName("生产环境未配置密钥 → 启动失败")
    void prodWithoutSecretRejected() {
        assertThrows(IllegalStateException.class, () -> new JwtTokenProvider(
                "", 7200000L, 604800000L, 7776000000L, 604800000L, "prod"));
    }

    @Test
    @DisplayName("开发环境未配置密钥 → 回退开发密钥可用")
    void devWithoutSecretFallsBack() {
        JwtTokenProvider devProvider = new JwtTokenProvider(
                "", 7200000L, 604800000L, 7776000000L, 604800000L, "dev");
        String token = devProvider.generateToken(USER_ID, "student", TENANT_ID);
        assertTrue(devProvider.validateToken(token));
    }
}
