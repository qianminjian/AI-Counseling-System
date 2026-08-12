package com.mindsafe.service.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TokenBlacklistService 测试（板块03 P1-6 补测：jti 黑名单）
 * <p>
 * 覆盖：拉黑写入（前缀 token:blacklist:jti: + TTL=剩余有效期）、
 * 已过期 token（remainingMs ≤ 0）不写、命中/未命中判定。
 * AUDIT-P1-13：黑名单粒度 jti（key 更短、不泄露 token 明文）。
 */
class TokenBlacklistServiceTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final TokenBlacklistService service = new TokenBlacklistService(redisTemplate);

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    @DisplayName("blacklist：jti + 剩余有效期毫秒写入 Redis（前缀 + TTL）")
    void blacklistSetsWithTtl() {
        service.blacklist("jti-abc", 300_000L);
        verify(valueOps).set(eq("token:blacklist:jti:jti-abc"), eq("1"),
                eq(300_000L), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    @DisplayName("blacklist：剩余有效期 ≤ 0（已过期 token）不写入")
    void blacklistSkipsExpired() {
        service.blacklist("jti-abc", 0L);
        service.blacklist("jti-abc", -1L);
        verify(valueOps, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    @DisplayName("isBlacklisted：命中返回 true / 未命中返回 false")
    void isBlacklisted() {
        when(redisTemplate.hasKey("token:blacklist:jti:jti-abc")).thenReturn(true);
        assertThat(service.isBlacklisted("jti-abc")).isTrue();

        when(redisTemplate.hasKey("token:blacklist:jti:jti-abc")).thenReturn(false);
        assertThat(service.isBlacklisted("jti-abc")).isFalse();
    }

    @Test
    @DisplayName("isBlacklisted：查询键带 jti 前缀（防止与其它缓存 key 冲突）")
    void isBlacklistedUsesPrefixedKey() {
        service.isBlacklisted("jti-xyz");
        verify(redisTemplate).hasKey("token:blacklist:jti:jti-xyz");
    }
}
