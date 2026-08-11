package com.mindsafe.service.usage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 每日使用时长限制服务（AUTH-030，未保法）
 * <p>
 * 按"租户 + 学生 + 自然日"累计对话活跃时长（秒），超过 {@code max-daily-minutes}（默认 30 分钟）
 * 后由对话层引导休息。红色风险消息不受限制（安全优先）。
 * <p>
 * Redis Key：{@code usage:time:{tenantId}:{userId}:{yyyyMMdd}}，TTL 2 天（跨日自动失效）。
 */
@Service
public class UsageTimeLimitService {

    private static final Logger log = LoggerFactory.getLogger(UsageTimeLimitService.class);
    private static final String KEY_PREFIX = "usage:time:";
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final Duration KEY_TTL = Duration.ofDays(2);

    private final StringRedisTemplate redisTemplate;
    private final int maxDailyMinutes;

    public UsageTimeLimitService(
            StringRedisTemplate redisTemplate,
            @Value("${mindsafe.security.usage-limit.max-daily-minutes:30}") int maxDailyMinutes) {
        this.redisTemplate = redisTemplate;
        this.maxDailyMinutes = maxDailyMinutes;
    }

    /** 累加使用时长（秒） */
    public void addUsage(UUID tenantId, UUID userId, long seconds) {
        if (seconds <= 0 || maxDailyMinutes <= 0) {
            return;
        }
        try {
            String key = key(tenantId, userId);
            Long total = redisTemplate.opsForValue().increment(key, seconds);
            if (total != null && total == seconds) {
                redisTemplate.expire(key, KEY_TTL);
            }
        } catch (Exception e) {
            log.warn("累计使用时长失败（不影响对话）: userId={}, error={}", userId, e.getMessage());
        }
    }

    /** 当日已使用秒数 */
    public long getUsedSeconds(UUID tenantId, UUID userId) {
        try {
            String val = redisTemplate.opsForValue().get(key(tenantId, userId));
            return val == null ? 0 : Long.parseLong(val);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 是否已达每日上限 */
    public boolean isExceeded(UUID tenantId, UUID userId) {
        if (maxDailyMinutes <= 0) {
            return false; // 管理员关闭限制
        }
        return getUsedSeconds(tenantId, userId) >= maxDailyMinutes * 60L;
    }

    /** 剩余可用秒数（已达上限返回 0） */
    public long getRemainingSeconds(UUID tenantId, UUID userId) {
        if (maxDailyMinutes <= 0) {
            return Long.MAX_VALUE;
        }
        long remaining = maxDailyMinutes * 60L - getUsedSeconds(tenantId, userId);
        return Math.max(0, remaining);
    }

    public int getMaxDailyMinutes() {
        return maxDailyMinutes;
    }

    private String key(UUID tenantId, UUID userId) {
        // doing/92 R-010：业务日界收敛至 CounselingTimeZone
        return KEY_PREFIX + tenantId + ":" + userId + ":" + com.mindsafe.service.common.CounselingTimeZone.today().format(DAY_FMT);
    }
}
