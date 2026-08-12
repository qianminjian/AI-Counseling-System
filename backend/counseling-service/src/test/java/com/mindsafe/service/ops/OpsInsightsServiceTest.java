package com.mindsafe.service.ops;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.mindsafe.domain.entity.QualityScore;
import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.mapper.ConsentRecordMapper;
import com.mindsafe.domain.mapper.NotificationMapper;
import com.mindsafe.domain.mapper.QualityScoreMapper;
import com.mindsafe.domain.mapper.RiskEventMapper;
import com.mindsafe.domain.mapper.TenantMapper;
import com.mindsafe.service.teacher.AlertSlaPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 运营洞察单元测试（ADMIN-P2-04/05，AC-P2-04/05）
 * 覆盖：渠道统计/dead 台账/质量趋势/预警漏斗/租户健康度
 */
class OpsInsightsServiceTest {

    private final NotificationMapper notificationMapper = mock(NotificationMapper.class);
    private final RiskEventMapper riskEventMapper = mock(RiskEventMapper.class);
    private final QualityScoreMapper qualityScoreMapper = mock(QualityScoreMapper.class);
    private final ConsentRecordMapper consentRecordMapper = mock(ConsentRecordMapper.class);
    private final TenantMapper tenantMapper = mock(TenantMapper.class);
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate =
            mock(org.springframework.jdbc.core.JdbcTemplate.class);
    // P1-2（板块05）：真实 AlertSlaPolicy 实例（纯函数无依赖）——逾期阈值走分级策略而非 mock
    private final AlertSlaPolicy slaPolicy = new AlertSlaPolicy();
    private final OpsInsightsService service =
            new OpsInsightsService(notificationMapper, riskEventMapper, qualityScoreMapper,
                    consentRecordMapper, tenantMapper, jdbcTemplate, slaPolicy);

    @Test
    @DisplayName("渠道统计：DB GROUP BY 按 channel 分组计数（P2-7）")
    void channelStatsGroups() {
        // P2-7：统计下推 DB，jdbcTemplate 直接返回分组结果（wecom=1, sms=2, null→unknown）
        when(jdbcTemplate.queryForList(anyString(), any(Timestamp.class)))
                .thenReturn(List.of(
                        Map.of("channel", "sms", "cnt", 2L),
                        Map.of("channel", "wecom", "cnt", 1L)));

        Map<String, Object> stats = service.channelStats();

        assertThat(stats.get("total")).isEqualTo(3L);
        @SuppressWarnings("unchecked")
        Map<String, Long> byChannel = (Map<String, Long>) stats.get("byChannel");
        assertThat(byChannel).containsEntry("wecom", 1L).containsEntry("sms", 2L);
    }

    @Test
    @DisplayName("预警漏斗：检出/通知/认领/处置/闭环 阶段计数")
    void alertFunnelCounts() {
        Instant now = Instant.now();
        RiskEvent open = new RiskEvent();
        open.setStatus(RiskEvent.STATUS_OPEN);
        open.setNotifyStatus("pending");
        open.setDetectedAt(now.minus(1, ChronoUnit.DAYS));
        RiskEvent claimed = new RiskEvent();
        claimed.setStatus(RiskEvent.STATUS_CLAIMED);
        claimed.setNotifyStatus("sent");
        claimed.setDetectedAt(now.minus(1, ChronoUnit.DAYS));
        RiskEvent closed = new RiskEvent();
        closed.setStatus(RiskEvent.STATUS_CLOSED);
        closed.setNotifyStatus("sent");
        closed.setDetectedAt(now.minus(2, ChronoUnit.DAYS));
        when(riskEventMapper.selectList(any(Wrapper.class))).thenReturn(List.of(open, claimed, closed));

        Map<String, Object> funnel = service.alertFunnel();

        assertThat(funnel.get("detected")).isEqualTo(3L);
        assertThat(funnel.get("notified")).isEqualTo(2L);
        assertThat(funnel.get("claimed")).isEqualTo(2L);
        assertThat(funnel.get("resolved")).isEqualTo(1L);
        assertThat(funnel.get("closed")).isEqualTo(1L);
    }

    @Test
    @DisplayName("质量趋势：近 7 天按日平均分 + 样本数")
    void qualityTrendDaily() {
        Instant now = Instant.now();
        QualityScore today = new QualityScore();
        today.setOverallScore(new BigDecimal("80"));
        today.setEvaluatedAt(now);
        QualityScore yesterday = new QualityScore();
        yesterday.setOverallScore(new BigDecimal("70"));
        yesterday.setEvaluatedAt(now.minus(1, ChronoUnit.DAYS));
        when(qualityScoreMapper.selectList(any(Wrapper.class))).thenReturn(List.of(today, yesterday));

        Map<String, Object> trend = service.qualityTrend();

        assertThat(trend).hasSize(7);
        // 最后一条（LinkedHashMap 插入序：6 天前 → 今天）为今天
        Object todayEntry = trend.values().stream().reduce((first, second) -> second).orElseThrow();
        assertThat(((Map<?, ?>) todayEntry).get("samples")).isEqualTo(1);
    }

    @Test
    @DisplayName("dead 台账：仅脱敏字段（无 studentUserId/schoolId，R-7）+ limit 钳制")
    void deadLedgerMasked() {
        RiskEvent e = new RiskEvent();
        e.setRiskEventId(UUID.randomUUID());
        e.setTenantId(UUID.randomUUID());
        e.setRiskLevel(3);
        e.setRiskType("CRISIS");
        e.setStatus(RiskEvent.STATUS_OPEN);
        e.setDetectedAt(Instant.now());
        e.setNotifyStatus("dead");
        e.setStudentUserId(UUID.randomUUID());
        // BACK-012（doing/95）：deadLedger 改 selectPage（AUD-043 风格统一），mock 同步
        when(riskEventMapper.selectPage(any(), any())).thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<RiskEvent>()
                {{ setRecords(List.of(e)); }});


        List<OpsInsightsService.DeadLedgerEntry> ledger = service.deadLedger(0);

        assertThat(ledger).hasSize(1);
        OpsInsightsService.DeadLedgerEntry entry = ledger.get(0);
        assertThat(entry.riskEventId()).isEqualTo(e.getRiskEventId());
        assertThat(entry.tenantId()).isEqualTo(e.getTenantId());
        assertThat(entry.notifyStatus()).isEqualTo("dead");
    }

    @Test
    @DisplayName("租户健康度：按租户聚合 total/unhandled/overdue + 健康档位（逾期按 SLA 分级阈值）")
    void tenantHealthAggregates() {
        Instant now = Instant.now();
        UUID tenantA = UUID.randomUUID();
        RiskEvent open = new RiskEvent();
        open.setTenantId(tenantA);
        open.setStatus(RiskEvent.STATUS_OPEN);
        open.setRiskLevel(3);                        // S0/RED：SLA 5 分钟
        open.setDetectedAt(now.minus(2, ChronoUnit.HOURS));   // 远超 5min → 逾期
        RiskEvent resolved = new RiskEvent();
        resolved.setTenantId(tenantA);
        resolved.setStatus(RiskEvent.STATUS_RESOLVED);
        resolved.setDetectedAt(now.minus(1, ChronoUnit.DAYS));
        when(riskEventMapper.selectList(any(Wrapper.class))).thenReturn(List.of(open, resolved));

        List<Map<String, Object>> health = service.tenantHealth();

        assertThat(health).hasSize(1);
        Map<String, Object> row = health.get(0);
        assertThat(row.get("tenantId")).isEqualTo(tenantA);
        assertThat(row.get("total")).isEqualTo(2);
        assertThat(row.get("unhandled")).isEqualTo(1L);
        assertThat(row.get("overdue")).isEqualTo(1L);
        assertThat(row.get("health")).isEqualTo("red");
    }

    @Test
    @DisplayName("P1-2 分级逾期：S2/YELLOW 60min 阈值内不逾期，超阈值才逾期")
    void tenantHealthRespectsSlaLevels() {
        Instant now = Instant.now();
        UUID tenantA = UUID.randomUUID();
        // riskLevel=1（S2/YELLOW，SLA 60min）：30 分钟前 → 未逾期；70 分钟前 → 逾期
        RiskEvent withinSla = new RiskEvent();
        withinSla.setTenantId(tenantA);
        withinSla.setStatus(RiskEvent.STATUS_OPEN);
        withinSla.setRiskLevel(1);
        withinSla.setDetectedAt(now.minus(30, ChronoUnit.MINUTES));
        RiskEvent beyondSla = new RiskEvent();
        beyondSla.setTenantId(tenantA);
        beyondSla.setStatus(RiskEvent.STATUS_OPEN);
        beyondSla.setRiskLevel(1);
        beyondSla.setDetectedAt(now.minus(70, ChronoUnit.MINUTES));
        // 无 riskLevel（S3/GREEN 无 SLA）：不参与逾期统计
        RiskEvent noLevel = new RiskEvent();
        noLevel.setTenantId(tenantA);
        noLevel.setStatus(RiskEvent.STATUS_OPEN);
        noLevel.setDetectedAt(now.minus(2, ChronoUnit.HOURS));
        when(riskEventMapper.selectList(any(Wrapper.class))).thenReturn(List.of(withinSla, beyondSla, noLevel));

        List<Map<String, Object>> health = service.tenantHealth();

        assertThat(health).hasSize(1);
        Map<String, Object> row = health.get(0);
        assertThat(row.get("unhandled")).isEqualTo(3L);
        // 仅 beyondSla（70min > 60min）计逾期；withinSla 未超、noLevel 无 SLA
        assertThat(row.get("overdue")).isEqualTo(1L);
    }

    @Test
    @DisplayName("用量报表：按 metric 聚合 + 窗口天数钳制（1~90）")
    void usageSummaryAggregates() {
        when(jdbcTemplate.queryForList(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.<Object>any())).thenReturn(List.of(
                java.util.Map.of("metric", "llm_call", "total", 100L),
                java.util.Map.of("metric", "active_student_snapshot", "total", 42L)));

        Map<String, Object> summary = service.usageSummary(0);

        assertThat(summary.get("windowDays")).isEqualTo(1);
        assertThat(summary.get("llm_call")).isEqualTo(100L);
        assertThat(summary.get("active_student_snapshot")).isEqualTo(42L);
    }

    @Test
    @DisplayName("合规视图：总数 + 近 7 天新增 + 类型分布")
    void consentStatsAggregates() {
        com.mindsafe.domain.entity.ConsentRecord recent = new com.mindsafe.domain.entity.ConsentRecord();
        recent.setConsentType("VOICEPRINT");
        recent.setConsentedAt(Instant.now());
        com.mindsafe.domain.entity.ConsentRecord old = new com.mindsafe.domain.entity.ConsentRecord();
        old.setConsentType("VOICEPRINT");
        old.setConsentedAt(Instant.now().minus(30, ChronoUnit.DAYS));
        when(consentRecordMapper.selectList(any())).thenReturn(List.of(recent, old));

        Map<String, Object> stats = service.consentStats();

        assertThat(stats.get("total")).isEqualTo(2);
        assertThat(stats.get("last7d")).isEqualTo(1L);
        assertThat(((Map<?, ?>) stats.get("byType")).get("VOICEPRINT")).isEqualTo(2L);
    }
}
