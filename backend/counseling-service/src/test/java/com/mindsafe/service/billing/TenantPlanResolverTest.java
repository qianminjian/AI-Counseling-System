package com.mindsafe.service.billing;

import com.mindsafe.domain.entity.Tenant;
import com.mindsafe.domain.mapper.TenantMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
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

    private static Tenant tenantWithStatus(String status) {
        Tenant t = new Tenant();
        t.setStatus(status);
        return t;
    }
}
