package com.mindsafe.service.auth;

/**
 * 登录锁定向统一接口（doing/89 N-001 认证域深化，AC-89-01/02）
 * <p>
 * 统一"登录防爆破"语义（checkLockout/recordFailure/clearFailures），
 * 三实现并排（计数模型不同不强行合并）：
 * - LoginLockoutService（按用户名/手机号，Redis）
 * - PlatformLoginGuard（按 IP）
 * - TocAuthService（按手机号频率限制）
 * 家长端（ParentAuthService）接入后消除零防护缺口（AC-89-01）。
 */
public interface LoginRateLimiter {

    /** 检查是否锁定：超限抛 BizException（UNAUTHORIZED + 剩余时间提示）。 */
    void checkLockout(String identifier);

    /** 记录一次失败（达到阈值触发锁定）。 */
    void recordFailure(String identifier);

    /** 清除失败计数（登录成功/解锁时）。 */
    void clearFailures(String identifier);
}
