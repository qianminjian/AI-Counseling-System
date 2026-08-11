package com.mindsafe.service.auth;

import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 登录失败锁定服务（AUTH-012）
 * 连续 5 次失败 → 锁定 15 分钟
 */
@Service
public class LoginLockoutService implements LoginRateLimiter {

    private static final String KEY_PREFIX = "login_fail:";
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private final StringRedisTemplate redisTemplate;

    public LoginLockoutService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 登录前检查是否被锁定
     * @param identifier 用户名/昵称
     */
    public void checkLockout(String identifier) {
        String key = KEY_PREFIX + identifier;
        String countStr = redisTemplate.opsForValue().get(key);
        if (countStr != null && Integer.parseInt(countStr) >= MAX_ATTEMPTS) {
            Long ttl = redisTemplate.getExpire(key);
            long minutes = (ttl != null && ttl > 0) ? (ttl / 60) + 1 : 15;
            throw new BizException(ErrorCode.RATE_LIMITED,
                    "登录失败次数过多，请 " + minutes + " 分钟后重试");
        }
    }

    /**
     * 记录登录失败
     */
    public void recordFailure(String identifier) {
        String key = KEY_PREFIX + identifier;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            redisTemplate.expire(key, LOCK_DURATION);
        }
    }

    /**
     * 登录成功后清除失败计数
     */
    public void clearFailures(String identifier) {
        redisTemplate.delete(KEY_PREFIX + identifier);
    }
}
