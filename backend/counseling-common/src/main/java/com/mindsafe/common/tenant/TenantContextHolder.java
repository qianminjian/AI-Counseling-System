package com.mindsafe.common.tenant;

import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * 租户上下文持有者（P-02 多租户拦截器的数据面入口）
 * <p>
 * 纯 {@link ThreadLocal}，不依赖 Spring / Security，可被所有模块（含持久层拦截器）访问。
 * 由认证过滤器在请求线程绑定当前租户，请求结束务必 {@link #clear()} 防止线程池串租户。
 * <p>
 * <b>fail-fast 语义（M1-003 收紧后）</b>：{@link #get()} 返回 {@code null} 且不在
 * {@link #isSystemScope() 系统作用域} 内时，租户行拦截器直接抛异常拒绝执行 SQL。
 * 合法的跨租户/无租户链路（登录/注册等前置认证、{@code @Scheduled} 全租户扫描）必须显式通过
 * {@link #runAsSystem(Runnable)} / {@link #callAsSystem(Callable)} 声明系统作用域，
 * 不再允许静默跳过注入。
 */
public final class TenantContextHolder {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();
    /** 系统作用域标记：显式声明的跨租户执行区间（前置认证/定时任务），拦截器跳过注入。 */
    private static final ThreadLocal<Boolean> SYSTEM_SCOPE = new ThreadLocal<>();

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
        SYSTEM_SCOPE.remove();
    }

    /** 当前线程是否处于显式系统作用域（跨租户执行区间）。 */
    public static boolean isSystemScope() {
        return Boolean.TRUE.equals(SYSTEM_SCOPE.get());
    }

    /**
     * 直接标记/取消当前线程的系统作用域。
     * <p>仅供异步上下文传播（TaskDecorator）使用，业务代码应使用
     * {@link #runAsSystem(Runnable)} / {@link #callAsSystem(Callable)}。
     */
    public static void setSystemScope(boolean system) {
        if (system) {
            SYSTEM_SCOPE.set(Boolean.TRUE);
        } else {
            SYSTEM_SCOPE.remove();
        }
    }

    /** 在系统作用域内执行无返回值动作（嵌套安全：结束后恢复进入前的标记）。 */
    public static void runAsSystem(Runnable action) {
        boolean previous = isSystemScope();
        SYSTEM_SCOPE.set(Boolean.TRUE);
        try {
            action.run();
        } finally {
            setSystemScope(previous);
        }
    }

    /** 在系统作用域内执行有返回值动作（嵌套安全：结束后恢复进入前的标记）。 */
    public static <T> T callAsSystem(Callable<T> action) {
        boolean previous = isSystemScope();
        SYSTEM_SCOPE.set(Boolean.TRUE);
        try {
            return action.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("系统作用域执行失败", e);
        } finally {
            setSystemScope(previous);
        }
    }
}
