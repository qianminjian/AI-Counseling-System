package com.mindsafe.service.risk;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.mapper.RiskEventMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 业务指标埋点单元测试（ADMIN-P1-06，AC-P1-06）
 * 覆盖：逾期 gauge 计数（SLA 超时口径）/dead gauge 计数/指标注册
 */
class RiskMetricsJobTest {

    private final RiskEventMapper mapper = mock(RiskEventMapper.class);
    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final RiskMetricsJob job = new RiskMetricsJob(mapper, registry);

    private RiskEvent event(int level, Instant detectedAt, String status, String notifyStatus) {
        RiskEvent e = new RiskEvent();
        e.setRiskEventId(UUID.randomUUID());
        e.setRiskLevel(level);
        e.setDetectedAt(detectedAt);
        e.setStatus(status);
        e.setNotifyStatus(notifyStatus);
        return e;
    }

    @Test
    @DisplayName("指标注册：mindsafe_risk_events_overdue / mindsafe_risk_notify_dead")
    void gaugesRegistered() {
        assertThat(registry.find("mindsafe_risk_events_overdue").gauge()).isNotNull();
        assertThat(registry.find("mindsafe_risk_notify_dead").gauge()).isNotNull();
    }

    @Test
    @DisplayName("刷新：逾期计数（超 SLA 未处置）+ dead 计数")
    void refreshCounts() {
        Instant now = Instant.now();
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                event(3, now.minus(1, ChronoUnit.HOURS), RiskEvent.STATUS_OPEN, "sent"),      // 逾期（>15min）
                event(3, now.minus(1, ChronoUnit.MINUTES), RiskEvent.STATUS_OPEN, "sent"),     // 未逾期
                event(1, now.minus(1, ChronoUnit.DAYS), RiskEvent.STATUS_CLAIMED, "sent")      // 逾期（>8h）
        ));
        when(mapper.selectCount(any(Wrapper.class))).thenReturn(3L);

        job.refresh();

        assertThat(registry.find("mindsafe_risk_events_overdue").gauge().value()).isEqualTo(2.0);
        assertThat(registry.find("mindsafe_risk_notify_dead").gauge().value()).isEqualTo(3.0);
    }
}
