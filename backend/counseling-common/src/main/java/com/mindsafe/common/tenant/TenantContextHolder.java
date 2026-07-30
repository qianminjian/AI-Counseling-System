package com.mindsafe.common.tenant;

import java.util.UUID;

/**
 * 租户上下文持有者（P-02 多租户拦截器的数据面入口）
 * <p>
 * 纯 {@link ThreadLocal}，不依赖 Spring / Security，可被所有模块（含持久层拦截器）访问。
 * 由认证过滤器在请求线程绑定当前租户，请求结束务必 {@link #clear()} 防止线程池串租户。
 * <p>
 * <b>无上下文语义（稳健渐进策略 B）</b>：{@link #get()} 返回 {@code null} 表示当前线程未绑定租户
 * （前置认证流程如登录/注册、{@code @Scheduled} 定时任务、Flyway 迁移、{@code @Async} 线程）。
 * 此时租户行拦截器跳过条件注入，交由调用方的显式 ID/手工过滤兜底，而非 fail-fast。
 */
public final class TenantContextHolder {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContextHolder() {
    }

    /** 绑定当前请求线程的租户。 */
    public static void set(UUID tenantId) {
        CURRENT.set(tenantId);
    }

    /** 当前线程绑定的租户；未绑定返回 {@code null}。 */
    public static UUID get() {
        return CURRENT.get();
    }

    /** 清除绑定（必须在请求结束的 finally 中调用）。 */
    public static void clear() {
        CURRENT.remove();
    }
}
