package com.mindsafe.api.ratelimit;

import com.mindsafe.common.tenant.TenantContextHolder;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * 基于 Redis 的滑动窗口限流器（对齐计划 Phase 2.5）
 * <p>
 * 策略：
 * - 每用户 30 次/分钟（对话消息）
 * - 会话轮次上限 12 轮/次（由 ConversationState.maxTurns 控制）
 * <p>
 * 实现：Redis INCR + EXPIRE 简单计数器（固定窗口，足够当前规模）。
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private static final String KEY_PREFIX = "ratelimit:";
    private static final int MAX_REQUESTS_PER_MINUTE = 30;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    /**
     * AUD-041：已认证用户限流 key 带租户段（ratelimit:{tenantId}:{action}:{userId}），
     * 与 session:state: 前缀对齐（ARCH-010 D2），防跨租户 key 碰撞/统计污染；
     * 无租户上下文（防御回退）时保持旧格式。公开端点重载（IP/指纹 key）不属租户维度，不添加。
     */
    private static String tenantSegmented(UUID tenantId) {
        return tenantId != null ? KEY_PREFIX + tenantId + ":" : KEY_PREFIX;
    }

    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    public RateLimiter(StringRedisTemplate redisTemplate, MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
    }

    /**
     * AUD-014：Redis 故障时 fail-open（可用性优先），但记录 Prometheus 计数供告警。
     */
    private void recordFailOpen(String action) {
        meterRegistry.counter("mindsafe_ratelimit_failopen_total", "action", action).increment();
    }

    /**
     * 检查用户是否超过限流阈值
     *
     * @param userId 用户 ID
     * @param action 操作类型（如 "chat_message"）
     * @return true = 允许通过，false = 已限流
     */
    public boolean tryAcquire(UUID userId, String action) {
        String key = tenantSegmented(TenantContextHolder.get()) + action + ":" + userId;
        Long count;
        try {
            count = redisTemplate.opsForValue().increment(key);
        } catch (Exception e) {
            // AUD-014：Redis 故障 fail-open 放行（可用性优先），计数供 Prometheus 告警
            recordFailOpen(action);
            log.error("Redis 限流异常，fail-open 放行: key={}, action={}", key, action, e);
            return true;
        }
        if (count == null) {
            recordFailOpen(action);
            log.warn("Redis 限流返回 null，fail-open 放行: key={}, action={}", key, action);
            return true;
        }
        if (count == 1) {
            // 首次请求，设置过期时间
            redisTemplate.expire(key, WINDOW);
        }
        if (count > MAX_REQUESTS_PER_MINUTE) {
            log.warn("限流触发: userId={}, action={}, count={}", userId, action, count);
            return false;
        }
        return true;
    }

    /**
     * 按任意键限流（用于无认证上下文的公开端点，如按 IP 限流声纹验证）
     *
     * @param key       限流键（如客户端 IP）
     * @param action    操作类型
     * @param maxInWindow 窗口内最大次数
     * @param window    时间窗口
     * @return true = 允许通过，false = 已限流
     */
    public boolean tryAcquire(String key, String action, int maxInWindow, Duration window) {
        String redisKey = KEY_PREFIX + action + ":" + key;
        Long count;
        try {
            count = redisTemplate.opsForValue().increment(redisKey);
        } catch (Exception e) {
            // AUD-014：Redis 故障 fail-open 放行（可用性优先），计数供 Prometheus 告警
            recordFailOpen(action);
            log.error("Redis 限流异常，fail-open 放行: redisKey={}, action={}", redisKey, action, e);
            return true;
        }
        if (count == null) {
            recordFailOpen(action);
            log.warn("Redis 限流返回 null，fail-open 放行: redisKey={}, action={}", redisKey, action);
            return true;
        }
        if (count == 1) {
            redisTemplate.expire(redisKey, window);
        }
        if (count > maxInWindow) {
            log.warn("限流触发: key={}, action={}, count={}", key, action, count);
            return false;
        }
        return true;
    }

    /**
     * 获取剩余配额
     */
    public int remainingQuota(UUID userId, String action) {
        String key = tenantSegmented(TenantContextHolder.get()) + action + ":" + userId;
        String val = redisTemplate.opsForValue().get(key);
        int current = val != null ? Integer.parseInt(val) : 0;
        return Math.max(0, MAX_REQUESTS_PER_MINUTE - current);
    }
}
