package com.mindsafe.service.billing;

import com.mindsafe.domain.entity.Tenant;
import com.mindsafe.domain.mapper.TenantMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 租户订阅计划解析（BILL-001，design/38 §4.2）
 * <p>
 * B-13：从 {@code EntitlementFilter} 下沉的分层封装（Filter → Service → Mapper），
 * 消除 filter 直连 {@link TenantMapper} 的分层纪律灰色区；
 * fail-open 降级与 Prometheus 计数随迁，行为与原 filter 实现逐条对齐。
 */
@Service
public class TenantPlanResolver {

    private static final Logger log = LoggerFactory.getLogger(TenantPlanResolver.class);

    private final TenantMapper tenantMapper;
    private final MeterRegistry meterRegistry;

    public TenantPlanResolver(TenantMapper tenantMapper, MeterRegistry meterRegistry) {
        this.tenantMapper = tenantMapper;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 解析租户订阅计划：从 tenantId 查 tenants 表 → 映射 Plan。
     * <ul>
     *   <li>null（未认证路径）→ STANDARD 放行（受 SecurityConfig 保护）</li>
     *   <li>active → STANDARD（当前无独立 plan 字段，active 租户享有标准版权益）</li>
     *   <li>trial → TRIAL</li>
     *   <li>suspended / 其他 / 查不到 → TRIAL（最严格权益）</li>
     *   <li>查库异常 → STANDARD（AUD-014 fail-open，计数供告警发现权益静默失效）</li>
     * </ul>
     */
    public EntitlementChecker.Plan resolve(UUID tenantId) {
        if (tenantId == null) {
            return EntitlementChecker.Plan.STANDARD;
        }
        try {
            Tenant tenant = tenantMapper.selectById(tenantId);
            if (tenant == null) {
                log.warn("租户不存在: tenantId={}", tenantId);
                return EntitlementChecker.Plan.TRIAL;
            }
            return mapStatusToPlan(tenant.getStatus());
        } catch (Exception e) {
            // 查库异常降级为 STANDARD，避免阻断核心业务
            // AUD-014：fail-open 保留但记录 Prometheus 计数，供告警发现权益静默失效
            meterRegistry.counter("mindsafe_entitlement_failopen_total").increment();
            log.error("解析租户计划异常，降级为 STANDARD: tenantId={}", tenantId, e);
            return EntitlementChecker.Plan.STANDARD;
        }
    }

    private static EntitlementChecker.Plan mapStatusToPlan(String status) {
        if (status == null) {
            return EntitlementChecker.Plan.TRIAL;
        }
        return switch (status) {
            case "active" -> EntitlementChecker.Plan.STANDARD;
            case "trial" -> EntitlementChecker.Plan.TRIAL;
            default -> EntitlementChecker.Plan.TRIAL; // suspended/inactive/未知 → 最严格
        };
    }
}
