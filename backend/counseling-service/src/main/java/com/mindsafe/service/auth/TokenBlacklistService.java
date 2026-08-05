package com.mindsafe.service.auth;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Token 黑名单服务（登出后使 token 立即失效）
 * <p>
 * 原理：将已登出 token 的 JWT ID（jti）存入 Redis，TTL = token 剩余有效期。
 * JwtAuthenticationFilter 每次请求检查黑名单。
 * <p>
 * AUDIT-P1-13：黑名单粒度由完整 token 改为 jti（key 更短、不泄露 token 明文）。
 */
@Service
public class TokenBlacklistService {

    private static final String BLACKLIST_PREFIX = "token:blacklist:jti:";

    private final StringRedisTemplate redisTemplate;

    public TokenBlacklistService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 将 token 的 jti 加入黑名单
     *
     * @param tokenId     JWT ID（jti）
     * @param remainingMs 剩余有效毫秒数（到期后自动清除）
     */
    public void blacklist(String tokenId, long remainingMs) {
        if (remainingMs <= 0) return; // 已过期，无需拉黑
        redisTemplate.opsForValue().set(
                BLACKLIST_PREFIX + tokenId,
                "1",
                remainingMs,
                TimeUnit.MILLISECONDS
        );
    }

    /**
     * 检查 jti 是否在黑名单中
     */
    public boolean isBlacklisted(String tokenId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(BLACKLIST_PREFIX + tokenId));
    }
}
