package com.mindsafe.api.ratelimit;

import com.mindsafe.common.tenant.TenantContextHolder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RateLimiter Redis 固定窗口限流器单测：用户维度/任意键维度双入口、
 * 首次置 TTL、超阈值拒绝、Redis 异常与 null 返回 fail-open、剩余配额。
 */
@ExtendWith(MockitoExtension.class)
class RateLimiterTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private MeterRegistry meterRegistry;
    @Mock private Counter counter;

    private RateLimiter limiter;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // recordFailOpen 使用带 tag 的 counter(String, String...) 重载（varargs → String[]）
        lenient().when(meterRegistry.counter(anyString(), org.mockito.ArgumentMatchers.any(String[].class))).thenReturn(counter);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        limiter = new RateLimiter(redisTemplate, meterRegistry);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("tryAcquire(用户)：首次请求置 TTL 并放行")
    void tryAcquire_first() {
        when(valueOps.increment(anyString())).thenReturn(1L);

        assertThat(limiter.tryAcquire(userId, "chat_message")).isTrue();

        verify(redisTemplate).expire(anyString(), org.mockito.ArgumentMatchers.any(Duration.class));
    }

    @Test
    @DisplayName("tryAcquire(用户)：超阈值（31 次）拒绝")
    void tryAcquire_overLimit() {
        when(valueOps.increment(anyString())).thenReturn(31L);

        assertThat(limiter.tryAcquire(userId, "chat_message")).isFalse();
    }

    @Test
    @DisplayName("tryAcquire(用户)：阈值内放行（30 次）")
    void tryAcquire_withinLimit() {
        when(valueOps.increment(anyString())).thenReturn(30L);

        assertThat(limiter.tryAcquire(userId, "chat_message")).isTrue();
    }

    @Test
    @DisplayName("tryAcquire(用户)：Redis 异常 fail-open 并记录 Prometheus 计数")
    void tryAcquire_redisError() {
        when(valueOps.increment(anyString())).thenThrow(new RuntimeException("conn refused"));

        assertThat(limiter.tryAcquire(userId, "chat_message")).isTrue();

        verify(counter).increment();
    }

    @Test
    @DisplayName("tryAcquire(用户)：increment 返回 null fail-open")
    void tryAcquire_nullCount() {
        when(valueOps.increment(anyString())).thenReturn(null);

        assertThat(limiter.tryAcquire(userId, "chat_message")).isTrue();

        verify(counter).increment();
    }

    @Test
    @DisplayName("tryAcquire(用户)：携带租户上下文时 key 带租户段（行为等价即可）")
    void tryAcquire_withTenant() {
        TenantContextHolder.set(UUID.randomUUID());
        when(valueOps.increment(anyString())).thenReturn(1L);

        assertThat(limiter.tryAcquire(userId, "chat_message")).isTrue();
        // key 前缀含租户段：验证 increment 的 key 以 ratelimit:{tenant}: 开头
        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(valueOps).increment(captor.capture());
        assertThat(captor.getValue()).startsWith("ratelimit:").contains("chat_message").endsWith(userId.toString());
    }

    @Test
    @DisplayName("tryAcquire(任意键)：按自定义窗口/上限判定")
    void tryAcquire_arbitraryKey() {
        when(valueOps.increment(anyString())).thenReturn(1L);
        assertThat(limiter.tryAcquire("1.2.3.4", "voiceprint_verify", 5, Duration.ofMinutes(1))).isTrue();
        verify(redisTemplate).expire(anyString(), org.mockito.ArgumentMatchers.any(Duration.class));

        when(valueOps.increment(anyString())).thenReturn(6L);
        assertThat(limiter.tryAcquire("1.2.3.4", "voiceprint_verify", 5, Duration.ofMinutes(1))).isFalse();
    }

    @Test
    @DisplayName("tryAcquire(任意键)：Redis 异常 fail-open")
    void tryAcquire_arbitraryKey_redisError() {
        when(valueOps.increment(anyString())).thenThrow(new RuntimeException("boom"));

        assertThat(limiter.tryAcquire("1.2.3.4", "voiceprint_verify", 5, Duration.ofMinutes(1))).isTrue();
        verify(counter).increment();
    }

    @Test
    @DisplayName("remainingQuota：有计数按 30 减、无记录满额、超限归 0")
    void remainingQuota() {
        when(valueOps.get(anyString())).thenReturn(null);
        assertThat(limiter.remainingQuota(userId, "chat_message")).isEqualTo(30);

        when(valueOps.get(anyString())).thenReturn("10");
        assertThat(limiter.remainingQuota(userId, "chat_message")).isEqualTo(20);

        when(valueOps.get(anyString())).thenReturn("40");
        assertThat(limiter.remainingQuota(userId, "chat_message")).isZero();
    }
}
