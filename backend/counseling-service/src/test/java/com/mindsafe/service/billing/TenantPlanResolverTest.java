package com.mindsafe.service.billing;

import com.mindsafe.domain.entity.Tenant;
import com.mindsafe.domain.mapper.TenantMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-13：租户订阅计划解析（下沉自 EntitlementFilter 的 resolveTenantPlan）。
 * 行为与 filter 原实现逐条对齐：null→STANDARD / active→STANDARD / trial→TRIAL /
 * 其他→TRIAL / 查不到→TRIAL / 查库异常→STANDARD（fail-open）。
 */
class TenantPlanResolverTest {

    private final TenantMapper tenantMapper = mock(TenantMapper.class);
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final TenantPlanResolver resolver = new TenantPlanResolver(tenantMapper, meterRegistry);

    @Test
    void 无租户上下文_返回STANDARD放行() {
        assertEquals(EntitlementChecker.Plan.STANDARD, resolver.resolve(null));
    }

    @Test
    void 租户active_映射STANDARD() {
        UUID tenantId = UUID.randomUUID();
        when(tenantMapper.selectById(tenantId)).thenReturn(tenantWithStatus("active"));
        assertEquals(EntitlementChecker.Plan.STANDARD, resolver.resolve(tenantId));
    }

    @Test
    void 租户trial_映射TRIAL() {
        UUID tenantId = UUID.randomUUID();
        when(tenantMapper.selectById(tenantId)).thenReturn(tenantWithStatus("trial"));
        assertEquals(EntitlementChecker.Plan.TRIAL, resolver.resolve(tenantId));
    }

    @Test
    void 租户suspended或未知_映射TRIAL最严格() {
        UUID tenantId = UUID.randomUUID();
        when(tenantMapper.selectById(tenantId)).thenReturn(tenantWithStatus("suspended"));
        assertEquals(EntitlementChecker.Plan.TRIAL, resolver.resolve(tenantId));
        when(tenantMapper.selectById(tenantId)).thenReturn(tenantWithStatus("weird"));
        assertEquals(EntitlementChecker.Plan.TRIAL, resolver.resolve(tenantId));
    }

    @Test
    void 租户不存在_映射TRIAL() {
        UUID tenantId = UUID.randomUUID();
        when(tenantMapper.selectById(tenantId)).thenReturn(null);
        assertEquals(EntitlementChecker.Plan.TRIAL, resolver.resolve(tenantId));
    }

    @Test
    void 查库异常_降级STANDARD并计数failopen() {
        UUID tenantId = UUID.randomUUID();
        when(tenantMapper.selectById(tenantId)).thenThrow(new RuntimeException("db down"));
        assertEquals(EntitlementChecker.Plan.STANDARD, resolver.resolve(tenantId));
        assertEquals(1.0, meterRegistry.counter("mindsafe_entitlement_failopen_total").count());
    }

    // ===== doing/92 R-013：短 TTL 本地缓存 =====

    @Test
    void 缓存命中_不再查库() {
        UUID tenantId = UUID.randomUUID();
        when(tenantMapper.selectById(tenantId)).thenReturn(tenantWithStatus("active"));

        assertEquals(EntitlementChecker.Plan.STANDARD, resolver.resolve(tenantId));
        assertEquals(EntitlementChecker.Plan.STANDARD, resolver.resolve(tenantId));

        verify(tenantMapper, times(1)).selectById(tenantId);
    }

    @Test
    void TTL过期_重新查库() throws InterruptedException {
        UUID tenantId = UUID.randomUUID();
        // 包可见构造：50ms 短 TTL 可控验证过期路径
        TenantPlanResolver shortTtl = new TenantPlanResolver(tenantMapper, meterRegistry, 50);
        when(tenantMapper.selectById(tenantId)).thenReturn(tenantWithStatus("trial"));

        assertEquals(EntitlementChecker.Plan.TRIAL, shortTtl.resolve(tenantId));
        assertEquals(EntitlementChecker.Plan.TRIAL, shortTtl.resolve(tenantId));
        verify(tenantMapper, times(1)).selectById(tenantId);

        Thread.sleep(80);
        assertEquals(EntitlementChecker.Plan.TRIAL, shortTtl.resolve(tenantId));
        verify(tenantMapper, times(2)).selectById(tenantId);
    }

    @Test
    void failopen异常_不写缓存_下次重试查库() {
        UUID tenantId = UUID.randomUUID();
        when(tenantMapper.selectById(tenantId))
                .thenThrow(new RuntimeException("db down"))
                .thenReturn(tenantWithStatus("active"));

        assertEquals(EntitlementChecker.Plan.STANDARD, resolver.resolve(tenantId));
        // 服务恢复后下次请求立即重试（不被 fail-open 缓存掩盖）
        assertEquals(EntitlementChecker.Plan.STANDARD, resolver.resolve(tenantId));
        verify(tenantMapper, times(2)).selectById(tenantId);
    }

    private static Tenant tenantWithStatus(String status) {
        Tenant t = new Tenant();
        t.setStatus(status);
        return t;
    }
}
