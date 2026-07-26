package com.mindsafe.service.auth;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Token 黑名单服务（登出后使 token 立即失效）
 * <p>
 * 原理：将已登出的 token 存入 Redis，TTL = token 剩余有效期。
 * JwtAuthenticationFilter 每次请求检查黑名单。
 */
@Service
public class TokenBlacklistService {

    private static final String BLACKLIST_PREFIX = "token:blacklist:";

    private final StringRedisTemplate redisTemplate;

    public TokenBlacklistService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 将 token 加入黑名单
     *
     * @param token      JWT token
     * @param remainingMs 剩余有效毫秒数（到期后自动清除）
     */
    public void blacklist(String token, long remainingMs) {
        if (remainingMs <= 0) return; // 已过期，无需拉黑
        redisTemplate.opsForValue().set(
                BLACKLIST_PREFIX + token,
                "1",
                remainingMs,
                TimeUnit.MILLISECONDS
        );
    }

    /**
     * 检查 token 是否在黑名单中
     */
    public boolean isBlacklisted(String token) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(BLACKLIST_PREFIX + token));
    }
}
