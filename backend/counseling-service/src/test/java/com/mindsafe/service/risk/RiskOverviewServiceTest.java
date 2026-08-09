package com.mindsafe.service.risk;

import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.mapper.RiskEventMapper;
import com.mindsafe.domain.mapper.SlaEscalationLogMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 风险全景与时效统计单元测试（ADMIN-P1-04，AC-P1-04）
 * 覆盖：红橙黄绿分布/今日新增/未处置/7 天趋势/SLA 达标率与 P95
 */
class RiskOverviewServiceTest {

    private final RiskEventMapper mapper = mock(RiskEventMapper.class);
    private final RiskOverviewService service =
            new RiskOverviewService(mapper, mock(SlaEscalationLogMapper.class));

    private RiskEvent event(int level, Instant detectedAt, Instant resolvedAt, String status) {
        RiskEvent e = new RiskEvent();
        e.setRiskEventId(UUID.randomUUID());
        e.setTenantId(UUID.randomUUID());
        e.setRiskLevel(level);
        e.setDetectedAt(detectedAt);
        e.setResolvedAt(resolvedAt);
        e.setStatus(status);
        return e;
    }

    @Test
    @DisplayName("风险全景：红橙黄绿分布 + 今日新增 + 未处置 + 7 天趋势")
    void overviewAggregates() {
        Instant now = Instant.now();
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                event(3, now.minus(1, ChronoUnit.HOURS), now, RiskEvent.STATUS_RESOLVED),   // 今日 RED 已处置
                event(2, now.minus(2, ChronoUnit.HOURS), null, RiskEvent.STATUS_OPEN),       // 今日 ORANGE 未处置
                event(1, now.minus(3, ChronoUnit.DAYS), null, RiskEvent.STATUS_CLAIMED),     // YELLOW 未处置
                event(0, now.minus(5, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS), RiskEvent.STATUS_CLOSED)
        ));

        Map<String, Object> overview = service.overview(null);

        assertThat(overview.get("todayNew")).isEqualTo(2L);
        assertThat(overview.get("unhandled")).isEqualTo(2L);
        @SuppressWarnings("unchecked")
        Map<String, Long> dist = (Map<String, Long>) overview.get("levelDistribution");
        assertThat(dist).containsEntry("red", 1L).containsEntry("orange", 1L).containsEntry("yellow", 1L).containsEntry("green", 1L);
        @SuppressWarnings("unchecked")
        Map<String, Long> trend = (Map<String, Long>) overview.get("trend7d");
        assertThat(trend).hasSize(7);
    }

    @Test
    @DisplayName("时效监控：达标率按等级聚合 + P95 处理时长")
    void slaStatsAggregates() {
        Instant now = Instant.now();
        // RED（SLA 15min）：5 条 10min 达标 + 5 条 60min 逾期 → 达标率 50%，P95=60
        List<RiskEvent> reds = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            reds.add(event(3, now.minus(10, ChronoUnit.MINUTES), now, RiskEvent.STATUS_RESOLVED));
        }
        for (int i = 0; i < 5; i++) {
            reds.add(event(3, now.minus(60, ChronoUnit.MINUTES), now, RiskEvent.STATUS_RESOLVED));
        }
        when(mapper.selectList(any(Wrapper.class))).thenReturn(reds);

        List<Map<String, Object>> stats = service.slaStats(null);

        assertThat(stats).hasSize(1);
        Map<String, Object> row = stats.get(0);
        assertThat(row.get("riskLevel")).isEqualTo(3);
        assertThat(row.get("total")).isEqualTo(10);
        assertThat(row.get("onTime")).isEqualTo(5L);
        assertThat(row.get("overdue")).isEqualTo(5L);
        assertThat((Double) row.get("onTimeRate")).isEqualTo(50.0);
        assertThat(row.get("p95Minutes")).isEqualTo(60L);
    }
}
