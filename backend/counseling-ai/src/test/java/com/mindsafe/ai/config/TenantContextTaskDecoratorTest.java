package com.mindsafe.ai.config;

import com.mindsafe.common.tenant.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TenantContextTaskDecorator（BA-15：异步租户上下文传播）单元测试
 * <p>
 * 覆盖：tenantId/systemScope 传播、执行后 finally 清理、异常时也清理、无租户时不设置。
 * 注：ThreadLocal 线程隔离，所有清理断言必须在子线程内（wrapped.run() 返回后）完成。
 */
class TenantContextTaskDecoratorTest {

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("子线程执行前恢复调用线程的 tenantId 与 systemScope，执行后 finally 清理")
    void decorate_propagatesTenantAndSystemScope() throws Exception {
        UUID tenantId = UUID.randomUUID();
        TenantContextHolder.set(tenantId);
        TenantContextHolder.setSystemScope(true);

        Runnable wrapped = new TenantContextTaskDecorator().decorate(() -> {
            assertThat(TenantContextHolder.get()).as("子线程应有调用线程租户").isEqualTo(tenantId);
            assertThat(TenantContextHolder.isSystemScope()).as("子线程应继承系统作用域").isTrue();
        });

        CountDownLatch executed = new CountDownLatch(1);
        AtomicReference<AssertionError> workerError = new AtomicReference<>();
        AtomicReference<UUID> afterRunTenant = new AtomicReference<>();
        AtomicReference<Boolean> afterRunScope = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                wrapped.run();
            } catch (AssertionError e) {
                workerError.set(e);
            }
            afterRunTenant.set(TenantContextHolder.get());
            afterRunScope.set(TenantContextHolder.isSystemScope());
            executed.countDown();
        }, "test-decorator-propagate");
        worker.start();

        assertThat(executed.await(5, TimeUnit.SECONDS)).as("任务应在超时前完成").isTrue();
        worker.join(1_000);

        assertThat(workerError.get()).as("子线程应继承调用线程租户上下文").isNull();
        assertThat(afterRunTenant.get()).as("子线程执行后应清理租户").isNull();
        assertThat(afterRunScope.get()).as("子线程执行后应清理系统作用域").isFalse();
    }

    @Test
    @DisplayName("任务抛异常时同样在 finally 清理子线程上下文")
    void decorate_clearsEvenOnException() throws Exception {
        TenantContextHolder.set(UUID.randomUUID());

        Runnable wrapped = new TenantContextTaskDecorator().decorate(() -> {
            throw new IllegalStateException("boom");
        });

        CountDownLatch executed = new CountDownLatch(1);
        AtomicReference<Throwable> workerThrow = new AtomicReference<>();
        AtomicReference<UUID> afterRunTenant = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                wrapped.run();
            } catch (IllegalStateException e) {
                workerThrow.set(e);
            }
            afterRunTenant.set(TenantContextHolder.get());
            executed.countDown();
        }, "test-decorator-exception");
        worker.start();

        assertThat(executed.await(5, TimeUnit.SECONDS)).as("任务应在超时前执行").isTrue();
        worker.join(1_000);

        assertThat(workerThrow.get()).as("异常应传播给调用方线程池").isInstanceOf(IllegalStateException.class);
        assertThat(afterRunTenant.get()).as("异常路径也应清理租户").isNull();
    }

    @Test
    @DisplayName("调用线程无租户时，子线程不设置上下文（保持 null，交由拦截器 fail-fast 兜底）")
    void decorate_noTenant_noSet() throws Exception {
        CountDownLatch executed = new CountDownLatch(1);
        AtomicReference<UUID> workerTenant = new AtomicReference<>();

        Runnable wrapped = new TenantContextTaskDecorator().decorate(() -> {
            workerTenant.set(TenantContextHolder.get());
            executed.countDown();
        });

        Thread worker = new Thread(wrapped, "test-decorator-no-tenant");
        worker.start();
        assertThat(executed.await(5, TimeUnit.SECONDS)).as("任务应在超时前完成").isTrue();
        worker.join(1_000);

        assertThat(workerTenant.get()).as("无租户时子线程应为 null").isNull();
    }
}
