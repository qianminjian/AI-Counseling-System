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
 * <p>
 * 契约说明（P1 审计 03-P1-3）：recordFailure/clearFailures 允许空实现——
 * 各实现采用不同防爆破模型，不强制全部实现失败计数语义：
 * TocAuthService 以「每分钟窗口频率限制」覆盖失败锁定语义（窗口自动过期，
 * 无持久失败计数），故 recordFailure/clearFailures 为有意 no-op；
 * 调用方不应依赖 recordFailure 后立即生效的计数效果，应依赖 checkLockout 的
 * 模型自身（频率窗口 / 失败计数 / IP 锁定）完成防护。
 */
public interface LoginRateLimiter {

    /** 检查是否锁定：超限抛 BizException（UNAUTHORIZED + 剩余时间提示）。 */
    void checkLockout(String identifier);

    /** 记录一次失败（达到阈值触发锁定）。 */
    void recordFailure(String identifier);

    /** 清除失败计数（登录成功/解锁时）。 */
    void clearFailures(String identifier);
}
