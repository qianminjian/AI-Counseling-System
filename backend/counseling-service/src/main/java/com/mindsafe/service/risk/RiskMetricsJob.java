package com.mindsafe.service.risk;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.common.tenant.TenantContextHolder;
import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.mapper.RiskEventMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 业务指标埋点（ADMIN-P1-06，M8 业务级告警数据源，§5.8 8.6）
 * <p>
 * 30s 周期产出 mindsafe_risk_* gauge（R-11 隐私边界：仅计数/时长，绝不含学生/教师标识）：
 * - mindsafe_risk_events_overdue_total  当前逾期未处置预警数（SLA 超时口径与平台查询一致）
 * - mindsafe_risk_notify_dead_total     通知 dead 堆积数（notify_status=dead）
 * 规则消费见 alert-rules.yml 业务段（MindsafeRiskOverdueHigh / MindsafeNotifyDeadPiling）。
 */
@Component
public class RiskMetricsJob {

    private static final Logger log = LoggerFactory.getLogger(RiskMetricsJob.class);

    /** SLA 处置阈值（分钟）——与 RiskOverviewService 同口径（权威 RiskLevel：RED=3/ORANGE=2/YELLOW=1/GREEN=0） */
    private static final Map<Integer, Long> SLA_DISPOSE_MINUTES = Map.of(
            3, 15L, 2, 60L, 1, 480L, 0, 1440L);

    private final RiskEventMapper riskEventMapper;
    private final AtomicLong overdueGauge = new AtomicLong(0);
    private final AtomicLong deadGauge = new AtomicLong(0);

    public RiskMetricsJob(RiskEventMapper riskEventMapper, MeterRegistry meterRegistry) {
        this.riskEventMapper = riskEventMapper;
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
        });
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
