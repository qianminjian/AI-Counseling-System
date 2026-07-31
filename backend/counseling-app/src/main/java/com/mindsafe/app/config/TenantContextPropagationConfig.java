package com.mindsafe.app.config;

import com.mindsafe.common.tenant.TenantContextHolder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;

import java.util.UUID;

/**
 * 租户上下文异步传播配置（M1-003 fail-fast 收紧配套）
 * <p>
 * {@code @Async} 方法运行在线程池子线程，ThreadLocal 不会自动传播。收紧前依赖
 * 「无上下文静默跳过注入」带病运行；收紧后无上下文直接 fail-fast，故必须在任务提交时
 * 捕获调用方线程的租户上下文/系统作用域，并在子线程执行期间还原。
 * <p>
 * Spring Boot {@code TaskExecutionAutoConfiguration} 会自动将本 {@link TaskDecorator}
 * 应用到默认 {@code applicationTaskExecutor}（即 {@code @Async} 的缺省执行器）。
 */
@Configuration
public class TenantContextPropagationConfig {

    @Bean
    public TaskDecorator tenantContextTaskDecorator() {
        return runnable -> {
            // 提交时刻：捕获调用方线程的租户与系统作用域
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
                    // 线程池复用，务必清除防止串租户
                    TenantContextHolder.clear();
                }
            };
        };
    }
}
