package com.mindsafe.ai.config;

import com.mindsafe.common.tenant.TenantContextHolder;
import org.springframework.core.task.TaskDecorator;

import java.util.UUID;

/**
 * 租户上下文异步传播装饰器（BA-15，配合 TenantContextHolder#setSystemScope 的注释约定）
 * <p>
 * 提交时捕获调用线程的租户上下文/系统作用域，子线程执行前恢复、finally 清除；
 * 否则异步任务内的 DB 写入在无上下文下触发租户行隔离 fail-fast。
 * <p>
 * 用法：ThreadPoolTaskExecutor#setTaskDecorator(new TenantContextTaskDecorator())。
 * 业务代码不应手动捕获/恢复上下文（历史 A1 手动版已收敛至此）。
 */
public class TenantContextTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        UUID tenantId = TenantContextHolder.get();
        boolean systemScope = TenantContextHolder.isSystemScope();
        return () -> {
            try {
                if (tenantId != null) {
                    TenantContextHolder.set(tenantId);
                }
                TenantContextHolder.setSystemScope(systemScope);
                runnable.run();
            } finally {
                TenantContextHolder.clear();
            }
        };
    }
}
