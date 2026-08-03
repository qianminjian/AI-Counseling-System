package com.mindsafe.api.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 令牌工具（双 Token 模式：access 2h + refresh 7d；另有声纹设备凭证 90d）
 * <p>
 * 生产环境必须配置 mindsafe.jwt.secret（≥ 32 字符），否则启动失败。
 */
@Component
public class JwtTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);
    private static final String DEV_SECRET = "mindsafe-dev-secret-key-at-least-256-bits-long!!";

    private final SecretKey key;
    private final long accessExpirationMs;
    private final long refreshExpirationMs;
    private final long voiceCredentialExpirationMs;
    private final long parentReportExpirationMs;

    public JwtTokenProvider(
            @Value("${mindsafe.jwt.secret:}") String secret,
            @Value("${mindsafe.jwt.access-expiration-ms:7200000}") long accessExpirationMs,
            @Value("${mindsafe.jwt.refresh-expiration-ms:604800000}") long refreshExpirationMs,
            @Value("${mindsafe.jwt.voice-credential-expiration-ms:7776000000}") long voiceCredentialExpirationMs,
            @Value("${mindsafe.jwt.parent-report-expiration-ms:604800000}") long parentReportExpirationMs,
            @Value("${spring.profiles.active:dev}") String activeProfile) {

        // 生产环境强制配置密钥
        if (secret == null || secret.isBlank()) {
            if ("prod".equals(activeProfile) || "production".equals(activeProfile)) {
                throw new IllegalStateException(
                        "[FATAL] mindsafe.jwt.secret 未配置！生产环境禁止使用默认密钥。" +
                        "请设置环境变量 MINDSAFE_JWT_SECRET（≥ 32 字符随机串）");
            }
            log.warn("[JWT] 使用开发默认密钥，仅限本地开发！生产必须配置 mindsafe.jwt.secret");
            secret = DEV_SECRET;
        } else if (secret.length() < 32) {
            throw new IllegalStateException(
                    "[FATAL] mindsafe.jwt.secret 长度不足 32 字符，不安全！");
        }

        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessExpirationMs = accessExpirationMs;
        this.refreshExpirationMs = refreshExpirationMs;
        this.voiceCredentialExpirationMs = voiceCredentialExpirationMs;
        this.parentReportExpirationMs = parentReportExpirationMs;
    }

    /** 生成 Access Token（2h） */
    public String generateToken(UUID userId, String userType, UUID tenantId) {
        return buildToken(userId, userType, tenantId, "access", accessExpirationMs);
    }

    /** 生成 Refresh Token（7d） */
    public String generateRefreshToken(UUID userId, String userType, UUID tenantId) {
        return buildToken(userId, userType, tenantId, "refresh", refreshExpirationMs);
    }

    /** 生成声纹设备凭证（90d）：声纹录入时签发，存学生设备本地，声纹登录时凭其换取正式双 token */
    public String generateVoiceCredential(UUID userId, String userType, UUID tenantId) {
        return buildToken(userId, userType, tenantId, "voice_credential", voiceCredentialExpirationMs);
    }

    /** 生成家长报告链接 token（默认 7d，SEC-006）：独立 tokenType，与学生 access token 区分，防学生自持 token 调家长接口 */
    public String generateParentReportToken(UUID studentUserId, UUID tenantId) {
        return buildToken(studentUserId, "parent", tenantId, "parent_report", parentReportExpirationMs);
    }

    private String buildToken(UUID userId, String userType, UUID tenantId, String tokenType, long ttl) {
        Date now = new Date();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("userType", userType)
                .claim("tenantId", tenantId.toString())
                .claim("tokenType", tokenType)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttl))
                .signWith(key)
                .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    /** 验证是否为 access token（防止用 refresh token 访问 API） */
    public boolean isAccessToken(String token) {
        try {
            Claims claims = parseToken(token);
            return "access".equals(claims.get("tokenType", String.class));
        } catch (Exception e) {
            return false;
        }
    }

    /** 验证是否为 refresh token */
    public boolean isRefreshToken(String token) {
        try {
            Claims claims = parseToken(token);
            return "refresh".equals(claims.get("tokenType", String.class));
        } catch (Exception e) {
            return false;
        }
    }

    /** 验证是否为声纹设备凭证 */
    public boolean isVoiceCredential(String token) {
        try {
            Claims claims = parseToken(token);
            return "voice_credential".equals(claims.get("tokenType", String.class));
        } catch (Exception e) {
            return false;
        }
    }

    /** 验证是否为家长报告链接 token（SEC-006） */
    public boolean isParentReportToken(String token) {
        try {
            Claims claims = parseToken(token);
            return "parent_report".equals(claims.get("tokenType", String.class));
        } catch (Exception e) {
            return false;
        }
    }

    public UUID getUserId(String token) {
        return UUID.fromString(parseToken(token).getSubject());
    }

    public String getUserType(String token) {
        return parseToken(token).get("userType", String.class);
    }

    public UUID getTenantId(String token) {
        return UUID.fromString(parseToken(token).get("tenantId", String.class));
    }

    /** 获取 token 过期时间（用于黑名单 TTL 计算） */
    public Date getExpiration(String token) {
        return parseToken(token).getExpiration();
    }

    /** 获取 token 剩余有效毫秒数 */
    public long getRemainingMs(String token) {
        Date exp = getExpiration(token);
        return Math.max(0, exp.getTime() - System.currentTimeMillis());
    }
}
