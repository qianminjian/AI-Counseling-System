package com.mindsafe.service.risk;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.common.tenant.TenantContextHolder;
import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.mapper.RiskEventMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 业务指标埋点（ADMIN-P1-06，M8 业务级告警数据源，§5.8 8.6）
 * <p>
 * 30s 周期产出 mindsafe_risk_* gauge（R-11 隐私边界：仅计数/时长，绝不含学生/教师标识）：
 * - mindsafe_risk_events_overdue  当前逾期未处置预警数（SLA 超时口径与平台查询一致）
 * - mindsafe_risk_notify_dead     通知 dead 堆积数（notify_status=dead）
 * - mindsafe_risk_events_24h / mindsafe_risk_claimed_24h  近 24h 按租户（tenant_id 标签，激增/认领率规则数据源）
 * 规则消费见 alert-rules.yml 业务段（MindsafeRiskOverdueHigh / MindsafeNotifyDeadPiling / MindsafeRiskSurge / MindsafeClaimRateDrop）。
 */
@Component
public class RiskMetricsJob {

    private static final Logger log = LoggerFactory.getLogger(RiskMetricsJob.class);

    /** SLA 处置阈值（分钟）——与 RiskOverviewService 同口径（权威 RiskLevel：RED=3/ORANGE=2/YELLOW=1/GREEN=0） */
    private static final Map<Integer, Long> SLA_DISPOSE_MINUTES = Map.of(
            3, 15L, 2, 60L, 1, 480L, 0, 1440L);

    /** 租户级指标保留期：连续 7 天无事件即注销 gauge（防已停用租户序列永久输出，M2） */
    private static final Duration TENANT_IDLE_RETENTION = Duration.ofDays(7);

    private final RiskEventMapper riskEventMapper;
    private final AtomicLong overdueGauge = new AtomicLong(0);
    private final AtomicLong deadGauge = new AtomicLong(0);
    private final MeterRegistry meterRegistry;

    /** 租户级指标（缺口 1：激增/认领率规则数据源；tenant_id 标签，R-11 仅计数） */
    private final Map<String, AtomicLong> events24hByTenant = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> claimed24hByTenant = new ConcurrentHashMap<>();
    /** 租户最后活跃时间（回收判据） */
    private final Map<String, Instant> lastActiveByTenant = new ConcurrentHashMap<>();

    public RiskMetricsJob(RiskEventMapper riskEventMapper, MeterRegistry meterRegistry) {
        this.riskEventMapper = riskEventMapper;
        this.meterRegistry = meterRegistry;
        Gauge.builder("mindsafe_risk_events_overdue", overdueGauge, AtomicLong::get)
                .description("当前逾期未处置预警数（SLA 超时口径，M8）")
                .register(meterRegistry);
        Gauge.builder("mindsafe_risk_notify_dead", deadGauge, AtomicLong::get)
                .description("通知 dead 堆积数（notify_status=dead，M8）")
                .register(meterRegistry);
    }

    @Scheduled(cron = "${mindsafe.monitoring.risk-metrics.cron:*/30 * * * * ?}")
    public void refresh() {
        TenantContextHolder.runAsSystem(() -> {
            overdueGauge.set(countOverdue());
            deadGauge.set(countDead());
            refreshTenantGauges();
        });
    }

    /** 租户级 24h 指标（缺口 1：mindsafe_risk_events_24h / mindsafe_risk_claimed_24h，按租户标签） */
    private void refreshTenantGauges() {
        // 先清零已注册租户（窗口内无新事件的租户归零，防陈旧值导致激增/认领率规则失真）
        events24hByTenant.values().forEach(v -> v.set(0));
        claimed24hByTenant.values().forEach(v -> v.set(0));

        Instant since = Instant.now().minus(1, ChronoUnit.DAYS);
        List<RiskEvent> recent = riskEventMapper.selectList(new LambdaQueryWrapper<RiskEvent>()
                .ge(RiskEvent::getDetectedAt, since));
        Map<String, long[]> counts = new HashMap<>();
        for (RiskEvent e : recent) {
            String tenant = e.getTenantId() == null ? "platform" : e.getTenantId().toString();
            long[] c = counts.computeIfAbsent(tenant, k -> new long[2]);
            c[0]++;
            if (RiskEvent.STATUS_CLAIMED.equals(e.getStatus())
                    || RiskEvent.STATUS_RESOLVED.equals(e.getStatus())
                    || RiskEvent.STATUS_CLOSED.equals(e.getStatus())) {
                c[1]++;
            }
        }
        for (Map.Entry<String, long[]> entry : counts.entrySet()) {
            AtomicLong eventsGauge = events24hByTenant.computeIfAbsent(entry.getKey(),
                    k -> registerTenantGauge("mindsafe_risk_events_24h", k));
            AtomicLong claimedGauge = claimed24hByTenant.computeIfAbsent(entry.getKey(),
                    k -> registerTenantGauge("mindsafe_risk_claimed_24h", k));
            eventsGauge.set(entry.getValue()[0]);
            claimedGauge.set(entry.getValue()[1]);
            lastActiveByTenant.put(entry.getKey(), Instant.now());
        }
        retireIdleTenants();
    }

    /** 注销连续 7 天无事件的租户 gauge（防序列无界增长；已停用租户不再输出 0 值） */
    private void retireIdleTenants() {
        Instant cutoff = Instant.now().minus(TENANT_IDLE_RETENTION);
        for (String tenant : events24hByTenant.keySet()) {
            Instant last = lastActiveByTenant.get(tenant);
            if (last != null && last.isBefore(cutoff)) {
                meterRegistry.remove(meterRegistry.find("mindsafe_risk_events_24h")
                        .tags("tenant_id", tenant).gauge());
                meterRegistry.remove(meterRegistry.find("mindsafe_risk_claimed_24h")
                        .tags("tenant_id", tenant).gauge());
                events24hByTenant.remove(tenant);
                claimed24hByTenant.remove(tenant);
                lastActiveByTenant.remove(tenant);
                log.info("租户 {} 连续 {} 天无预警事件，注销其业务指标 gauge", tenant, TENANT_IDLE_RETENTION.toDays());
            }
        }
    }

    private AtomicLong registerTenantGauge(String name, String tenantId) {
        AtomicLong value = new AtomicLong(0);
        Gauge.builder(name, value, AtomicLong::get)
                .description("近 24h 按租户（M8 业务规则数据源，缺口 1）")
                .tags(Tags.of("tenant_id", tenantId))
                .register(meterRegistry);
        return value;
    }

    private long countOverdue() {
        Instant now = Instant.now();
        return riskEventMapper.selectList(new LambdaQueryWrapper<RiskEvent>()
                        .in(RiskEvent::getStatus, RiskEvent.STATUS_OPEN, RiskEvent.STATUS_CLAIMED))
                .stream()
                .filter(e -> e.getDetectedAt() != null)
                .filter(e -> Duration.between(e.getDetectedAt(), now).toMinutes()
                        > SLA_DISPOSE_MINUTES.getOrDefault(e.getRiskLevel() == null ? 0 : e.getRiskLevel(), 1440L))
                .count();
    }

    private long countDead() {
        return riskEventMapper.selectCount(new LambdaQueryWrapper<RiskEvent>()
                .eq(RiskEvent::getNotifyStatus, "dead"));
    }
}
