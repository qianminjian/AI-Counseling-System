package com.mindsafe.api.security;

import io.jsonwebtoken.Claims;
import com.mindsafe.domain.entity.PlatformAdmin;
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
    private static final String DEV_SECRET = "dev-only-secret-key-at-least-32-characters-long-xyz";
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID TENANT_ID = UUID.randomUUID();

    private final JwtTokenProvider provider = new JwtTokenProvider(
            SECRET, 7200000L, 604800000L, 604800000L, 604800000L, "dev", DEV_SECRET, "mindsafe-test");

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
        // AUDIT-P1-13：iss/aud/jti 齐全且可提取
        assertEquals("mindsafe-test", claims.getIssuer());
        assertEquals("mindsafe-api", claims.getAudience().iterator().next());
        assertNotNull(claims.getId());
        assertNotNull(provider.getTokenId(token));
        assertEquals(claims.getId(), provider.getTokenId(token));

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
                SECRET, 50L, 604800000L, 604800000L, 604800000L, "dev", DEV_SECRET, "mindsafe-test");
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
                7200000L, 604800000L, 604800000L, 604800000L, "dev", DEV_SECRET, "mindsafe-test");
        String token = other.generateToken(USER_ID, "student", TENANT_ID);
        assertFalse(provider.validateToken(token));
    }

    @Test
    @DisplayName("其他 issuer 签发的 token 校验失败（AUDIT-P1-13）")
    void foreignIssuerRejected() {
        JwtTokenProvider foreignIssuer = new JwtTokenProvider(
                SECRET, 7200000L, 604800000L, 604800000L, 604800000L, "dev", DEV_SECRET, "evil-issuer");
        String token = foreignIssuer.generateToken(USER_ID, "student", TENANT_ID);
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
                "too-short", 7200000L, 604800000L, 604800000L, 604800000L, "dev", DEV_SECRET, "mindsafe-test"));
    }

    @Test
    @DisplayName("生产环境未配置密钥 → 启动失败")
    void prodWithoutSecretRejected() {
        assertThrows(IllegalStateException.class, () -> new JwtTokenProvider(
                "", 7200000L, 604800000L, 604800000L, 604800000L, "prod", DEV_SECRET, "mindsafe-test"));
    }

    @Test
    @DisplayName("开发环境未配置密钥 → 回退 dev-secret 可用（AUDIT-P3-28）")
    void devWithoutSecretFallsBack() {
        JwtTokenProvider devProvider = new JwtTokenProvider(
                "", 7200000L, 604800000L, 604800000L, 604800000L, "dev", DEV_SECRET, "mindsafe-test");
        String token = devProvider.generateToken(USER_ID, "student", TENANT_ID);
        assertTrue(devProvider.validateToken(token));
    }

    @Test
    @DisplayName("开发环境 dev-secret 缺失 → 启动失败（AUDIT-P3-28）")
    void devWithoutDevSecretRejected() {
        assertThrows(IllegalStateException.class, () -> new JwtTokenProvider(
                "", 7200000L, 604800000L, 604800000L, 604800000L, "dev", "", "mindsafe-test"));
        assertThrows(IllegalStateException.class, () -> new JwtTokenProvider(
                "", 7200000L, 604800000L, 604800000L, 604800000L, "dev", "too-short", "mindsafe-test"));
    }

    // ===== 平台 token（ADMIN-P0-02，R-8：PLATFORM_ 前缀独立登录态） =====

    @Test
    @DisplayName("平台 token：PLATFORM_ 前缀 + 角色 claim + 无租户依赖")
    void platformTokenGeneratedWithPrefix() {
        JwtTokenProvider provider = new JwtTokenProvider(
                "", 7200000L, 604800000L, 604800000L, 604800000L, "dev", DEV_SECRET, "mindsafe-test");
        String token = provider.generatePlatformToken(USER_ID, PlatformAdmin.ROLE_SUPER_ADMIN);

        assertTrue(token.startsWith(JwtTokenProvider.PLATFORM_TOKEN_PREFIX));
        assertTrue(provider.isPlatformToken(token));
        assertEquals(PlatformAdmin.ROLE_SUPER_ADMIN, provider.getPlatformRole(token));
        assertEquals(USER_ID, provider.getPlatformAdminId(token));
    }

    @Test
    @DisplayName("业务 token 不是平台 token（隔离验证）")
    void businessTokenNotPlatform() {
        JwtTokenProvider provider = new JwtTokenProvider(
                "", 7200000L, 604800000L, 604800000L, 604800000L, "dev", DEV_SECRET, "mindsafe-test");
        String businessToken = provider.generateToken(USER_ID, "student", TENANT_ID);

        assertFalse(provider.isPlatformToken(businessToken));
        assertFalse(provider.isPlatformToken(null));
        assertFalse(provider.isPlatformToken("PLATFORM_garbage"));
    }

    // ===== R-016 tokenType 枚举化（字面量单点收敛） =====

    @Test
    @DisplayName("parseOnce：五种合法类型均映射枚举，claim 值保持兼容")
    void parseOnce_mapsAllTokenTypes() {
        assertEquals(TokenType.ACCESS, provider.parseOnce(provider.generateToken(USER_ID, "student", TENANT_ID)).tokenType());
        assertEquals(TokenType.REFRESH, provider.parseOnce(provider.generateRefreshToken(USER_ID, "teacher", TENANT_ID)).tokenType());
        assertEquals(TokenType.VOICE_CREDENTIAL, provider.parseOnce(provider.generateVoiceCredential(USER_ID, "student", TENANT_ID)).tokenType());
        assertEquals(TokenType.PARENT_REPORT, provider.parseOnce(provider.generateParentReportToken(USER_ID, TENANT_ID)).tokenType());
        // 平台 token 走前缀剥离路径（8/10 事故根因所在，语义最特殊）
        assertEquals(TokenType.PLATFORM_ACCESS,
                provider.parseOnce(provider.generatePlatformToken(USER_ID, "super_admin")).tokenType());
    }

    @Test
    @DisplayName("parseOnce：未知 tokenType claim → null（filter 按非 ACCESS 拒绝，安全默认）")
    void parseOnce_unknownTokenTypeRejected() throws Exception {
        // 手工签发未知 tokenType 的 token（绕过枚举生成器）
        JwtTokenProvider provider = new JwtTokenProvider(
                "", 7200000L, 604800000L, 604800000L, 604800000L, "dev", DEV_SECRET, "mindsafe-test");
        var field = JwtTokenProvider.class.getDeclaredField("key");
        field.setAccessible(true);
        var key = (javax.crypto.SecretKey) field.get(provider);
        String rogue = io.jsonwebtoken.Jwts.builder()
                .subject(USER_ID.toString())
                .claim("userType", "student")
                .claim("tenantId", TENANT_ID.toString())
                .claim("tokenType", "super_secret_magic")
                .issuer("mindsafe-test")
                .audience().add("mindsafe-api").and()
                .issuedAt(new java.util.Date())
                .expiration(new java.util.Date(System.currentTimeMillis() + 3600_000))
                .signWith(key)
                .compact();

        assertNull(provider.parseOnce(rogue).tokenType());
        assertFalse(provider.isAccessToken(rogue));
    }
}
