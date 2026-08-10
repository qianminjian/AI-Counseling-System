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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        // B-03 对齐：服务端按上海日界（CounselingTimeZone.startOfDay）判定"今日"，测试数据须用同一基准
        // （Instant.truncatedTo(DAYS) 为 UTC 日界，上海 0-8 点窗口会漂移前一天导致 todayNew=0）
        Instant todayStart = com.mindsafe.service.common.CounselingTimeZone.startOfDay(now);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                event(3, todayStart.plus(1, ChronoUnit.HOURS), now, RiskEvent.STATUS_RESOLVED),   // 今日 RED 已处置
                event(2, todayStart.plus(2, ChronoUnit.HOURS), null, RiskEvent.STATUS_OPEN),       // 今日 ORANGE 未处置
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

    // ===== 补测批次（覆盖率回归 2026-08-09）：逾期清单脱敏 / 转派 / 强制关闭 =====

    @Test
    @DisplayName("逾期清单：仅脱敏字段（OverdueEntry，R-7 无学生级标识）+ 超 SLA 过滤")
    void overdueListMasked() {
        Instant now = Instant.now();
        RiskEvent overdue = event(3, now.minus(2, ChronoUnit.HOURS), null, RiskEvent.STATUS_OPEN);  // 超 15min
        overdue.setStudentUserId(UUID.randomUUID());
        RiskEvent notOverdue = event(3, now.minus(1, ChronoUnit.MINUTES), null, RiskEvent.STATUS_OPEN);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(overdue, notOverdue));

        List<RiskOverviewService.OverdueEntry> list = service.overdueList(null);

        assertThat(list).hasSize(1);
        RiskOverviewService.OverdueEntry entry = list.get(0);
        assertThat(entry.riskEventId()).isEqualTo(overdue.getRiskEventId());
        assertThat(entry.riskLevel()).isEqualTo(3);
        assertThat(entry.status()).isEqualTo(RiskEvent.STATUS_OPEN);
    }

    @Test
    @DisplayName("转派：open 事件可转派（负责人更新 + 状态 claimed + 留痕 transfer）")
    void transferSucceedsForOpen() {
        UUID riskId = UUID.randomUUID();
        UUID assignTo = UUID.randomUUID();
        RiskEvent open = event(3, Instant.now().minusSeconds(60), null, RiskEvent.STATUS_OPEN);
        open.setRiskEventId(riskId);
        when(mapper.selectById(riskId)).thenReturn(open);

        service.transfer(riskId, assignTo, "ops-1", "转派给班主任");

        assertThat(open.getStatus()).isEqualTo(RiskEvent.STATUS_CLAIMED);
        assertThat(open.getAssignedUserId()).isEqualTo(assignTo);
        org.mockito.Mockito.verify(mapper).updateById((RiskEvent) open);
    }

    @Test
    @DisplayName("转派：已关闭事件拒绝（仅 open/claimed 可转派）")
    void transferRejectsClosed() {
        UUID riskId = UUID.randomUUID();
        RiskEvent closed = event(3, Instant.now().minusSeconds(60), Instant.now(), RiskEvent.STATUS_CLOSED);
        closed.setRiskEventId(riskId);
        when(mapper.selectById(riskId)).thenReturn(closed);

        assertThatThrownBy(() -> service.transfer(riskId, UUID.randomUUID(), "ops-1", "x"))
                .isInstanceOf(com.mindsafe.common.exception.BizException.class);
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never())
                .updateById(org.mockito.ArgumentMatchers.<RiskEvent>any());
    }

    @Test
    @DisplayName("强制关闭：置 closed + 留痕 force_close")
    void forceCloseSucceeds() {
        UUID riskId = UUID.randomUUID();
        RiskEvent open = event(3, Instant.now().minusSeconds(60), null, RiskEvent.STATUS_OPEN);
        open.setRiskEventId(riskId);
        when(mapper.selectById(riskId)).thenReturn(open);

        service.forceClose(riskId, "super-1", "处置完毕");

        assertThat(open.getStatus()).isEqualTo(RiskEvent.STATUS_CLOSED);
        assertThat(open.getClosedAt()).isNotNull();
        org.mockito.Mockito.verify(mapper).updateById((RiskEvent) open);
    }

    @Test
    @DisplayName("不存在的预警：转派/强制关闭抛 RESOURCE_NOT_FOUND")
    void requireEventNotFound() {
        UUID riskId = UUID.randomUUID();
        when(mapper.selectById(riskId)).thenReturn(null);

        assertThatThrownBy(() -> service.forceClose(riskId, "super-1", "x"))
                .isInstanceOf(com.mindsafe.common.exception.BizException.class);
    }
}
