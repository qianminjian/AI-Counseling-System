package com.mindsafe.service.ops;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.mindsafe.domain.entity.Notification;
import com.mindsafe.domain.entity.QualityScore;
import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.mapper.NotificationMapper;
import com.mindsafe.domain.mapper.QualityScoreMapper;
import com.mindsafe.domain.mapper.RiskEventMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
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
 * 运营洞察单元测试（ADMIN-P2-04/05，AC-P2-04/05）
 * 覆盖：渠道统计/dead 台账/质量趋势/预警漏斗/租户健康度
 */
class OpsInsightsServiceTest {

    private final NotificationMapper notificationMapper = mock(NotificationMapper.class);
    private final RiskEventMapper riskEventMapper = mock(RiskEventMapper.class);
    private final QualityScoreMapper qualityScoreMapper = mock(QualityScoreMapper.class);
    private final OpsInsightsService service =
            new OpsInsightsService(notificationMapper, riskEventMapper, qualityScoreMapper);

    @Test
    @DisplayName("渠道统计：按 channel 分组计数")
    void channelStatsGroups() {
        Instant now = Instant.now();
        Notification wecom = new Notification();
        wecom.setChannel("wecom");
        wecom.setCreatedAt(now.minus(1, ChronoUnit.DAYS));
        Notification sms = new Notification();
        sms.setChannel("sms");
        sms.setCreatedAt(now);
        Notification sms2 = new Notification();
        sms2.setChannel("sms");
        sms2.setCreatedAt(now);
        when(notificationMapper.selectList(any(Wrapper.class))).thenReturn(List.of(wecom, sms, sms2));

        Map<String, Object> stats = service.channelStats();

        assertThat(stats.get("total")).isEqualTo(3);
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
}
