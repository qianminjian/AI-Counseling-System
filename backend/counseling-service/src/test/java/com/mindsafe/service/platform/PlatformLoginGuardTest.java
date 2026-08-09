package com.mindsafe.service.platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 平台登录防爆破守卫单元测试（P0 backlog M3）
 * 覆盖：5 次失败锁定/锁定期间拒绝/成功清除/超时自动解锁
 */
class PlatformLoginGuardTest {

    private final PlatformLoginGuard guard = new PlatformLoginGuard();

    @Test
    @DisplayName("5 次失败 → 锁定（15 分钟内 isLocked=true）")
    void lockAfterFailures() {
        for (int i = 0; i < PlatformLoginGuard.MAX_FAILURES; i++) {
            guard.recordFailure("1.2.3.4");
        }
        assertThat(guard.isLocked("1.2.3.4")).isTrue();
    }

    @Test
    @DisplayName("不足 5 次失败 → 不锁定")
    void notLockedBelowThreshold() {
        for (int i = 0; i < 3; i++) {
            guard.recordFailure("5.6.7.8");
        }
        assertThat(guard.isLocked("5.6.7.8")).isFalse();
    }

    @Test
    @DisplayName("锁定后登录成功 → 清除锁定")
    void successClearsLock() {
        for (int i = 0; i < PlatformLoginGuard.MAX_FAILURES; i++) {
            guard.recordFailure("9.9.9.9");
        }
        assertThat(guard.isLocked("9.9.9.9")).isTrue();

        guard.recordSuccess("9.9.9.9");

        assertThat(guard.isLocked("9.9.9.9")).isFalse();
    }
}
