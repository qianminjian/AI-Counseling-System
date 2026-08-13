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
 * JWT 令牌工具（双 Token 模式：access 2h + refresh 7d；另有声纹设备凭证 7d）
 * <p>
 * 生产环境必须配置 mindsafe.jwt.secret（≥ 32 字符），否则启动失败。
 * <p>
 * AUDIT-P1-13：token 携带 iss/aud/jti（黑名单按 jti 粒度）；AUDIT-P3-28：开发密钥不再硬编码，
 * 改由配置项 mindsafe.jwt.dev-secret 提供（仅非 prod 生效）。
 */
@Component
public class JwtTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);
    private static final String AUDIENCE = "mindsafe-api";

    /** 平台 token 前缀（R-8/DEC-007：独立前缀，平台登录态与业务登录态隔离） */
    public static final String PLATFORM_TOKEN_PREFIX = "PLATFORM_";

    /** 平台 token 的 userType 标记 */
    public static final String PLATFORM_USER_TYPE = "PLATFORM_ADMIN";

    /** Bearer 前缀（Authorization 头标准形式，F20 收编：三处 replace 手剥收敛） */
    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * 从 Authorization 头提取裸 token（F20，doing/97）：无 "Bearer " 前缀直接返回 null（拒绝），
     * 消除 replace("Bearer ", "") 无前缀校验的静默接受；null 由调用方按各自语义处理（401/幂等跳过）。
     */
    public static String extractBearerToken(String authHeader) {
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            return authHeader.substring(BEARER_PREFIX.length());
        }
        return null;
    }

    private final SecretKey key;
    private final String issuer;
    private final long accessExpirationMs;
    private final long refreshExpirationMs;
    private final long voiceCredentialExpirationMs;
    private final long parentReportExpirationMs;

    public JwtTokenProvider(
            @Value("${mindsafe.jwt.secret:}") String secret,
            @Value("${mindsafe.jwt.access-expiration-ms:7200000}") long accessExpirationMs,
            @Value("${mindsafe.jwt.refresh-expiration-ms:604800000}") long refreshExpirationMs,
            @Value("${mindsafe.jwt.voice-credential-expiration-ms:604800000}") long voiceCredentialExpirationMs,
            @Value("${mindsafe.jwt.parent-report-expiration-ms:604800000}") long parentReportExpirationMs,
            @Value("${spring.profiles.active:dev}") String activeProfile,
            @Value("${mindsafe.jwt.dev-secret:}") String devSecret,
            @Value("${mindsafe.jwt.issuer:mindsafe}") String issuer) {

        // 生产环境强制配置密钥
        if (secret == null || secret.isBlank()) {
            if ("prod".equals(activeProfile) || "production".equals(activeProfile)) {
                throw new IllegalStateException(
                        "[FATAL] mindsafe.jwt.secret 未配置！生产环境禁止使用默认密钥。" +
                        "请设置环境变量 MINDSAFE_JWT_SECRET（≥ 32 字符随机串）");
            }
            // AUDIT-P3-28：开发密钥从配置读取（application.yml mindsafe.jwt.dev-secret），不再硬编码于代码
            if (devSecret == null || devSecret.isBlank() || devSecret.length() < 32) {
                throw new IllegalStateException(
                        "[FATAL] mindsafe.jwt.dev-secret 未配置或长度不足 32 字符！" +
                        "本地开发请通过 MINDSAFE_JWT_DEV_SECRET 或 application.yml 配置");
            }
            log.warn("[JWT] 使用开发密钥（dev-secret），仅限本地开发！生产必须配置 mindsafe.jwt.secret");
            secret = devSecret;
        } else if (secret.length() < 32) {
            throw new IllegalStateException(
                    "[FATAL] mindsafe.jwt.secret 长度不足 32 字符，不安全！");
        }

        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.issuer = issuer;
        this.accessExpirationMs = accessExpirationMs;
        this.refreshExpirationMs = refreshExpirationMs;
        this.voiceCredentialExpirationMs = voiceCredentialExpirationMs;
        this.parentReportExpirationMs = parentReportExpirationMs;
    }

    /** 生成 Access Token（2h） */
    public String generateToken(UUID userId, String userType, UUID tenantId) {
        return buildToken(userId, userType, tenantId, TokenType.ACCESS, accessExpirationMs);
    }

    /** 生成 Refresh Token（7d） */
    public String generateRefreshToken(UUID userId, String userType, UUID tenantId) {
        return buildToken(userId, userType, tenantId, TokenType.REFRESH, refreshExpirationMs);
    }

    /**
     * 生成平台管理员 token（ADMIN-P0-02，R-8）——独立前缀 PLATFORM_ + tokenType=platform_access，
     * 无 tenantId claim（平台操作不属任何租户）；role claim 承载四角色。
     */
    public String generatePlatformToken(UUID adminId, String role) {
        Date now = new Date();
        String jwt = Jwts.builder()
                .subject(adminId.toString())
                .claim("userType", PLATFORM_USER_TYPE)
                .claim("role", role)
                .claim("tokenType", TokenType.PLATFORM_ACCESS.claimValue())
                .id(UUID.randomUUID().toString())
                .issuer(issuer)
                .audience().add(AUDIENCE).and()
                .issuedAt(now)
                .expiration(new Date(now.getTime() + accessExpirationMs))
                .signWith(key)
                .compact();
        return PLATFORM_TOKEN_PREFIX + jwt;
    }

    /** 是否为平台 token（前缀 + platform_access 类型双重校验） */
    public boolean isPlatformToken(String token) {
        if (token == null || !token.startsWith(PLATFORM_TOKEN_PREFIX)) {
            return false;
        }
        try {
            Claims claims = parseToken(stripPlatformPrefix(token));
            return TokenType.PLATFORM_ACCESS.claimValue().equals(claims.get("tokenType", String.class));
        } catch (Exception e) {
            return false;
        }
    }

    /** 平台 token 角色（super_admin/ops_admin/finance_admin/audit） */
    public String getPlatformRole(String token) {
        Claims claims = parseToken(stripPlatformPrefix(token));
        return claims.get("role", String.class);
    }

    /** 平台 token 管理员 ID */
    public UUID getPlatformAdminId(String token) {
        Claims claims = parseToken(stripPlatformPrefix(token));
        return UUID.fromString(claims.getSubject());
    }

    /** 生成声纹设备凭证（7d，AUD-001：90d → 7d 缩小攻击窗口）：声纹录入时签发，存学生设备本地，声纹登录时凭其换取正式双 token */
    public String generateVoiceCredential(UUID userId, String userType, UUID tenantId) {
        return buildToken(userId, userType, tenantId, TokenType.VOICE_CREDENTIAL, voiceCredentialExpirationMs);
    }

    /** 生成家长报告链接 token（默认 7d，SEC-006）：独立 tokenType，与学生 access token 区分，防学生自持 token 调家长 接口 */
    public String generateParentReportToken(UUID studentUserId, UUID tenantId) {
        return buildToken(studentUserId, "parent", tenantId, TokenType.PARENT_REPORT, parentReportExpirationMs);
    }
    
    /**
     * 家长登录 access token（BACK-008：家长域接口强制 PARENT_REPORT 类型，
     * 家长新登录若签发 ACCESS 会被 ParentIdentityResolver 拒 401——2026-08-13 遍历回归实测）。
     * sub=parentId（新登录语义），TTL 同普通 access。
     */
    public String generateParentLoginToken(UUID parentId, UUID tenantId) {
        return buildToken(parentId, "parent", tenantId, TokenType.PARENT_REPORT, accessExpirationMs);
    }

    private String buildToken(UUID userId, String userType, UUID tenantId, TokenType tokenType, long ttl) {
        Date now = new Date();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("userType", userType)
                .claim("tenantId", tenantId.toString())
                .claim("tokenType", tokenType.claimValue())
                // AUDIT-P1-13：jti（黑名单粒度）/ iss / aud
                .id(UUID.randomUUID().toString())
                .issuer(issuer)
                .audience().add(AUDIENCE).and()
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttl))
                .signWith(key)
                .compact();
    }

    /** doing/92 R-016：平台前缀剥离单点化（原 4 处重复——8/10 getTokenId 漏剥致平台域 500 事故根因） */
    private String stripPlatformPrefix(String token) {
        return token.substring(PLATFORM_TOKEN_PREFIX.length());
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** doing/92 R-017：单次 parse + 签名校验（filter 每请求 6 次 parse → 1 次），返回快照 */
    public ParsedToken parseOnce(String token) {
        if (token == null) {
            throw new com.mindsafe.common.exception.BizException(
                    com.mindsafe.common.dto.ErrorCode.UNAUTHORIZED, "token 缺失");
        }
        String raw = token.startsWith(PLATFORM_TOKEN_PREFIX) ? stripPlatformPrefix(token) : token;
        Claims claims = parseToken(raw);
        return new ParsedToken(
                claims.getId(),
                UUID.fromString(claims.getSubject()),
                claims.get("userType", String.class),
                claims.get("tenantId", String.class) == null ? null : UUID.fromString(claims.get("tenantId", String.class)),
                // doing/92 R-016：枚举化（未知 claim 值 → null，filter 按非 ACCESS 拒绝）
                TokenType.fromClaimValue(claims.get("tokenType", String.class)));
    }

    /** doing/92 R-017：单次 parse 快照（filter 认证链用） */
    public record ParsedToken(String tokenId, UUID userId, String userType, UUID tenantId, TokenType tokenType) {
    }

    /**
     * 宽容解析（等价旧 validateToken 语义：非法/过期 → null 而非抛异常）。
     * <p>
     * doing/92 R-017 单点化后，非 filter 消费方（refresh/声纹登录/登出/家长解析/WebSocket 握手）
     * 统一经本方法取值，不再 validate + isXxx + getXxx 多次 parse（审计 F2）。
     * 调用方仍须自行判 tokenType（ACCESS/REFRESH/VOICE_CREDENTIAL…），本方法只做签名/过期校验。
     */
    public ParsedToken parseOrNull(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            return parseOnce(token);
        } catch (Exception e) {
            return null;
        }
    }

    /** 获取 token 剩余有效毫秒数（黑名单 TTL 用；仅业务 token，无平台前缀） */
    public long getRemainingMs(String token) {
        Date exp = parseToken(token).getExpiration();
        return Math.max(0, exp.getTime() - System.currentTimeMillis());
    }

    /** 获取 token JWT ID（AUDIT-P1-13：黑名单按 jti 粒度，避免完整 token 作 Redis key） */
    public String getTokenId(String token) {
        // 修复（2026-08-10）：平台 token（PLATFORM_ 前缀）须先剥离前缀再解析——
        // 原实现对平台 token 直接 parseToken(完整串) → MalformedJwtException → 平台域 API 全部 500
        if (token != null && token.startsWith(PLATFORM_TOKEN_PREFIX)) {
            return parseToken(stripPlatformPrefix(token)).getId();
        }
        return parseToken(token).getId();
    }
}
