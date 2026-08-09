package com.mindsafe.app.config;

import com.mindsafe.common.tenant.TenantContextHolder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

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

    /**
     * BUG-TENANT-01：@Async 缺省执行器显式化（M1-003 fail-fast 收紧配套遗漏）。
     * <p>
     * Spring Boot {@code TaskExecutionAutoConfiguration} 的 {@code applicationTaskExecutor}
     * 条件为 {@code @ConditionalOnMissingBean(Executor.class)}——容器已存在 Executor bean
     * （AiConfig.outputReviewExecutor）时跳过创建，裸 {@code @Async} 落入
     * {@code SimpleAsyncTaskExecutor}（无装饰器），子线程丢失租户上下文触发 fail-fast
     * （生产日志：message_summaries/audit_logs 全量拒绝）。此处显式定义缺省执行器并挂装饰器根治。
     * <p>
     * 注意：返回类型必须声明为 {@link ThreadPoolTaskExecutor}（TaskExecutor 子类）而非 {@link Executor}——
     * Spring {@code @Async} 缺省执行器按 {@code TaskExecutor} 类型查找，返回 Executor 类型
     * 的 bean 无法被 {@code getBean(TaskExecutor.class)} 命中（首次修复失效原因）。
     * <p>
     * BUG-TENANT-01b：必须标注 {@code @Primary}——Boot 3.2+ 的 {@code AsyncExecutionAspectSupport}
     * 在多个 TaskExecutor bean（本执行器 + {@code taskScheduler}（ThreadPoolTaskScheduler 亦实现
     * TaskExecutor））且无 primary 时降级 {@code SimpleAsyncTaskExecutor}（生产线程名实证），
     * {@code @Primary} 使其在缺省解析中唯一命中。
     */
    @Bean(name = "applicationTaskExecutor")
    @Primary
    public ThreadPoolTaskExecutor applicationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("mindsafe-async-");
        executor.setTaskDecorator(tenantContextTaskDecorator());
        return executor;
    }
}
