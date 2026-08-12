package com.mindsafe.service.billing;

import com.mindsafe.domain.entity.Tenant;
import com.mindsafe.domain.mapper.TenantMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 租户订阅计划解析（BILL-001，design/38 §4.2）
 * <p>
 * B-13：从 {@code EntitlementFilter} 下沉的分层封装（Filter → Service → Mapper），
 * 消除 filter 直连 {@link TenantMapper} 的分层纪律灰色区；
 * fail-open 降级与 Prometheus 计数随迁，行为与原 filter 实现逐条对齐。
 * <p>
 * doing/92 R-013：每请求查库收敛——60s 短 TTL 本地缓存（租户状态低频变更，
 * 权益档位变更最迟 60s 生效）；fail-open 异常路径不缓存（下次请求重试，防掩盖恢复）。
 */
@Service
public class TenantPlanResolver {

    private static final Logger log = LoggerFactory.getLogger(TenantPlanResolver.class);

    /** 计划缓存 TTL（短 TTL：权益档位变更最迟 60s 生效） */
    private static final long CACHE_TTL_MS = 60_000;

    private final TenantMapper tenantMapper;
    private final MeterRegistry meterRegistry;

    /** 租户计划缓存（线程安全；fail-open 异常路径不写入） */
    private final ConcurrentHashMap<UUID, CachedPlan> planCache = new ConcurrentHashMap<>();

    /** 主构造器（Spring 注入；多构造器场景须显式 @Autowired，否则容器无法确定候选构造器） */
    @Autowired
    public TenantPlanResolver(TenantMapper tenantMapper, MeterRegistry meterRegistry) {
        this(tenantMapper, meterRegistry, CACHE_TTL_MS);
    }

    /** 测试可控 TTL 构造（包可见，默认 60s） */
    TenantPlanResolver(TenantMapper tenantMapper, MeterRegistry meterRegistry, long cacheTtlMs) {
        this.tenantMapper = tenantMapper;
        this.meterRegistry = meterRegistry;
        this.cacheTtlMs = cacheTtlMs;
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
        // doing/92 R-013：短 TTL 缓存命中直接返回（租户状态低频变更）
        CachedPlan cached = planCache.get(tenantId);
        if (cached != null && !cached.expired()) {
            return cached.plan();
        }
        try {
            Tenant tenant = tenantMapper.selectById(tenantId);
            EntitlementChecker.Plan plan = tenant == null
                    ? EntitlementChecker.Plan.TRIAL
                    : mapStatusToPlan(tenant.getStatus());
            planCache.put(tenantId, new CachedPlan(plan, System.currentTimeMillis() + cacheTtlMs));
            return plan;
        } catch (Exception e) {
            // 查库异常降级为 STANDARD，避免阻断核心业务
            // AUD-014：fail-open 保留但记录 Prometheus 计数，供告警发现权益静默失效
            meterRegistry.counter("mindsafe_entitlement_failopen_total").increment();
            log.error("解析租户计划异常，降级为 STANDARD: tenantId={}", tenantId, e);
            // 异常不写缓存：下次请求重试查库，防 fail-open 掩盖服务恢复
            return EntitlementChecker.Plan.STANDARD;
        }
    }

    /** 缓存条目（plan + 过期时间戳） */
    private record CachedPlan(EntitlementChecker.Plan plan, long expiresAt) {
        boolean expired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    private final long cacheTtlMs;

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
