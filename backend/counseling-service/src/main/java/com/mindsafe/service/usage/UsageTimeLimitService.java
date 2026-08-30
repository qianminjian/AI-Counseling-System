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
    private static final String WARN_KEY_PREFIX = "usage:warned:";
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final Duration KEY_TTL = Duration.ofDays(2);

    private final StringRedisTemplate redisTemplate;
    private final int maxDailyMinutes;
    /** 距上限不足该秒数时预警（默认 5 分钟），每学生每日最多提醒一次 */
    private final int warnRemainingSeconds;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;

    public UsageTimeLimitService(
            StringRedisTemplate redisTemplate,
            @Value("${mindsafe.security.usage-limit.max-daily-minutes:30}") int maxDailyMinutes,
            @Value("${mindsafe.security.usage-limit.warn-remaining-seconds:300}") int warnRemainingSeconds,
            io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.maxDailyMinutes = maxDailyMinutes;
        this.warnRemainingSeconds = warnRemainingSeconds;
        this.meterRegistry = meterRegistry;
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
            // doing/92 R-014：fail-open 保留但记录计数（对齐 AUD-014，供告警发现上限静默失效）
            meterRegistry.counter("mindsafe_usage_limit_failopen_total").increment();
            log.warn("累计使用时长失败（不影响对话）: userId={}, error={}", userId, e.getMessage());
        }
    }

    /** 当日已使用秒数 */
    public long getUsedSeconds(UUID tenantId, UUID userId) {
        try {
            String val = redisTemplate.opsForValue().get(key(tenantId, userId));
            return val == null ? 0 : Long.parseLong(val);
        } catch (Exception e) {
            meterRegistry.counter("mindsafe_usage_limit_failopen_total").increment();
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

    /**
     * AUTH-030：距上限不足预警检查（每学生每日最多一次）。
     * <p>
     * 剩余秒数 ≤ {@code warnRemainingSeconds} 且今日未提醒过 → 占位提醒标记并返回剩余秒数；
     * 否则返回 -1（不提醒）。占位失败（并发轮次已提醒）同样返回 -1。
     */
    public long tryMarkWarned(UUID tenantId, UUID userId) {
        if (maxDailyMinutes <= 0 || warnRemainingSeconds <= 0) {
            return -1;
        }
        long remaining = getRemainingSeconds(tenantId, userId);
        if (remaining > warnRemainingSeconds) {
            return -1;
        }
        try {
            Boolean first = redisTemplate.opsForValue().setIfAbsent(warnKey(tenantId, userId), "1", KEY_TTL);
            return Boolean.TRUE.equals(first) ? remaining : -1;
        } catch (Exception e) {
            meterRegistry.counter("mindsafe_usage_limit_failopen_total").increment();
            return -1;
        }
    }

    private String warnKey(UUID tenantId, UUID userId) {
        return WARN_KEY_PREFIX + tenantId + ":" + userId + ":" + com.mindsafe.service.common.CounselingTimeZone.today().format(DAY_FMT);
    }

    private String key(UUID tenantId, UUID userId) {
        // doing/92 R-010：业务日界收敛至 CounselingTimeZone
        return KEY_PREFIX + tenantId + ":" + userId + ":" + com.mindsafe.service.common.CounselingTimeZone.today().format(DAY_FMT);
    }
}
