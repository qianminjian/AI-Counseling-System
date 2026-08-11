package com.mindsafe.service.platform;

import com.mindsafe.common.exception.BizException;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.service.auth.LoginRateLimiter;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 平台登录防爆破守卫（ADMIN-P0 backlog M3：/api/v1/platform/auth/login 为公开端点）
 * <p>
 * 内存计数：同一 IP 连续失败 ≥5 次 → 锁定 15 分钟（期间拒绝登录）。
 * 仅防在线爆破（BCrypt 已减缓离线爆破）；分布式多实例场景后续可迁移 Redis。
 */
@Component
public class PlatformLoginGuard implements LoginRateLimiter {

    /** 失败阈值 */
    static final int MAX_FAILURES = 5;

    /** 锁定时长 */
    static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    /** 表项上限（code-review H3：防伪造 IP 撑爆内存，超限逐出最旧失败记录） */
    static final int MAX_ENTRIES = 100_000;

    /** IP → [失败计数, 最近失败时间] */
    private final ConcurrentHashMap<String, int[]> failures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> locks = new ConcurrentHashMap<>();

    /** 是否被锁定（锁定中返回 true） */
    public boolean isLocked(String clientIp) {
        Instant lockedUntil = locks.get(clientIp);
        if (lockedUntil == null) {
            return false;
        }
        if (Instant.now().isBefore(lockedUntil)) {
            return true;
        }
        locks.remove(clientIp);
        return false;
    }

    /** 记录失败（达到阈值即锁定；表项超限时逐出最旧——仅内存防放大，H3） */
    public void recordFailure(String clientIp) {
        if (failures.size() >= MAX_ENTRIES && !failures.containsKey(clientIp)) {
            String oldest = failures.keySet().iterator().next();
            failures.remove(oldest);
        }
        int[] counter = failures.computeIfAbsent(clientIp, k -> new int[1]);
        synchronized (counter) {
            counter[0]++;
            if (counter[0] >= MAX_FAILURES) {
                locks.put(clientIp, Instant.now().plus(LOCK_DURATION));
                failures.remove(clientIp);
            }
        }
    }

    /** 登录成功：清除失败记录与锁定 */
    public void recordSuccess(String clientIp) {
        failures.remove(clientIp);
        locks.remove(clientIp);
    }

    // ===== LoginRateLimiter 适配（doing/89 N-001） =====

    @Override
    public void checkLockout(String identifier) {
        if (isLocked(identifier)) {
            throw new BizException(ErrorCode.RATE_LIMITED, "失败次数过多，请 15 分钟后再试");
        }
    }

    @Override
    public void clearFailures(String identifier) {
        recordSuccess(identifier);
    }
}
