package com.mindsafe.api.controller;

import com.mindsafe.domain.entity.AlertEvent;
import com.mindsafe.domain.entity.AuditLog;
import com.mindsafe.domain.entity.ServiceHealthSnapshot;
import com.mindsafe.service.knowledge.KnowledgeBaseService;
import com.mindsafe.service.monitoring.DegradationMatrixService;
import com.mindsafe.service.monitoring.MetricsQueryService;
import com.mindsafe.service.monitoring.OpsService;
import com.mindsafe.service.ops.OpsInsightsService;
import com.mindsafe.service.risk.RiskOverviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OpsController 单元测试（ADMIN-P0-05/06/07：服务拓扑/告警只读/审计跨租户查询 HTTP 编排）
 */
class OpsControllerTest {

    private OpsService opsService;
    private RiskOverviewService riskOverviewService;
    private DegradationMatrixService degradationMatrixService;
    private KnowledgeBaseService knowledgeBaseService;
    private OpsInsightsService opsInsightsService;
    private MetricsQueryService metricsQueryService;
    private OpsController controller;

    @BeforeEach
    void setUp() {
        opsService = mock(OpsService.class);
        riskOverviewService = mock(RiskOverviewService.class);
        degradationMatrixService = mock(DegradationMatrixService.class);
        knowledgeBaseService = mock(KnowledgeBaseService.class);
        opsInsightsService = mock(OpsInsightsService.class);
        metricsQueryService = mock(MetricsQueryService.class);
        controller = new OpsController(opsService, riskOverviewService, degradationMatrixService, knowledgeBaseService, opsInsightsService, metricsQueryService);
    }

    @Test
    @DisplayName("GET /services/status → 六服务状态透传")
    void servicesStatus() {
        Map<String, String> statuses = new LinkedHashMap<>();
        statuses.put("postgres", "UP");
        statuses.put("redis", "UP");
        statuses.put("backend", "UP");
        statuses.put("tts", "DEGRADED");
        statuses.put("voice", "UP");
        statuses.put("nginx", "DOWN");
        when(opsService.servicesStatus()).thenReturn(statuses);

        var response = controller.servicesStatus();

        assertThat(response.data()).containsEntry("tts", "DEGRADED");
        assertThat(response.data()).containsEntry("nginx", "DOWN");
    }

    @Test
    @DisplayName("GET /services/health-history → 快照历史透传（服务过滤 + limit）")
    void healthHistory() {
        ServiceHealthSnapshot snapshot = new ServiceHealthSnapshot();
        snapshot.setService("tts");
        snapshot.setStatus("UP");
        when(opsService.healthHistory("tts", 50)).thenReturn(List.of(snapshot));

        var response = controller.healthHistory("tts", 50);

        assertThat(response.data()).hasSize(1);
        assertThat(response.data().get(0).getService()).isEqualTo("tts");
    }

    @Test
    @DisplayName("GET /alerts → AlertManager 告警只读透传")
    void alerts() {
        when(opsService.activeAlerts()).thenReturn(List.of(Map.<String, Object>of("fingerprint", "fp-1")));

        var response = controller.alerts();

        assertThat(response.data()).hasSize(1);
    }

    @Test
    @DisplayName("GET /audit-logs → 跨租户审计检索（tenantId 可空 = 平台级全量）")
    void auditLogs() {
        AuditLog log = new AuditLog();
        log.setAction("CONFIG_UPDATE");
        log.setTenantId(null);
        when(opsService.auditLogs(isNull(), eq("CONFIG_UPDATE"), isNull(), isNull(), eq(100)))
                .thenReturn(List.of(log));

        var response = controller.auditLogs(null, "CONFIG_UPDATE", null, null, 100);

        assertThat(response.data()).hasSize(1);
        assertThat(response.data().get(0).getAction()).isEqualTo("CONFIG_UPDATE");
    }

    @Test
    @DisplayName("GET /audit-logs → 租户级过滤透传")
    void auditLogsWithTenant() {
        UUID tenantId = UUID.randomUUID();
        Instant start = Instant.now().minusSeconds(3600);
        when(opsService.auditLogs(eq(tenantId), isNull(), eq(start), isNull(), eq(50)))
                .thenReturn(List.of());

        var response = controller.auditLogs(tenantId, null, start, null, 50);

        assertThat(response.data()).isEmpty();
        // 参数透传验证：直接用 mock 交互确认（上面 when 已断言参数匹配）
        org.mockito.Mockito.verify(opsService).auditLogs(eq(tenantId), isNull(), eq(start), isNull(), eq(50));
    }

    // ===== 补测批次（覆盖率回归 2026-08-09）：M8 风险/M3 降级/M9 知识/M10·M12 洞察/M4 用量/M11 合规 =====

    @Test
    @DisplayName("GET /risk/overview → 风险全景（tenantId 可空）")
    void riskOverview() {
        when(riskOverviewService.overview(isNull())).thenReturn(Map.of("total", 5));

        var response = controller.riskOverview(null);

        assertThat(response.data()).containsEntry("total", 5);
    }

    @Test
    @DisplayName("GET /risk/sla-stats → 时效统计透传")
    void riskSlaStats() {
        when(riskOverviewService.slaStats(isNull())).thenReturn(List.of(Map.of("riskLevel", 3)));

        var response = controller.riskSlaStats(null);

        assertThat(response.data()).hasSize(1);
    }

    @Test
    @DisplayName("GET /risk/overdue → 脱敏逾期清单（OverdueEntry，R-7）")
    void riskOverdue() {
        when(riskOverviewService.overdueList(isNull())).thenReturn(List.of());

        var response = controller.riskOverdue(null);

        assertThat(response.data()).isEmpty();
    }

    @Test
    @DisplayName("POST /risk/{id}/transfer → 缺 X-Confirm 拒绝（二次确认）")
    void transferRejectsWithoutConfirm() {
        var response = controller.transfer(UUID.randomUUID(), null,
                new OpsController.RiskTransferRequest(UUID.randomUUID(), "转派给班主任"));

        assertThat(response.code()).isNotEqualTo(0);
        org.mockito.Mockito.verifyNoInteractions(riskOverviewService);
    }

    @Test
    @DisplayName("POST /risk/{id}/transfer → 带 X-Confirm 转派成功（reason 必填）")
    void transferWithConfirm() {
        UUID riskId = UUID.randomUUID();
        UUID assignTo = UUID.randomUUID();
        org.springframework.security.authentication.UsernamePasswordAuthenticationToken auth =
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("ops-1", null, List.of());
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            var response = controller.transfer(riskId, "CONFIRM",
                    new OpsController.RiskTransferRequest(assignTo, "转派给班主任"));

            assertThat(response.code()).isEqualTo(0);
            org.mockito.Mockito.verify(riskOverviewService).transfer(riskId, assignTo, "ops-1", "转派给班主任");
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    @DisplayName("POST /risk/{id}/force-close → 带 X-Confirm 强制关闭成功")
    void forceCloseWithConfirm() {
        UUID riskId = UUID.randomUUID();
        org.springframework.security.authentication.UsernamePasswordAuthenticationToken auth =
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("super-1", null, List.of());
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            var response = controller.forceClose(riskId, "CONFIRM", new OpsController.RiskCloseRequest("处置完毕"));

            assertThat(response.code()).isEqualTo(0);
            org.mockito.Mockito.verify(riskOverviewService).forceClose(riskId, "super-1", "处置完毕");
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    @DisplayName("GET /degradation/matrix → 降级矩阵透传")
    void degradationMatrix() {
        when(degradationMatrixService.matrix()).thenReturn(List.of(Map.of("point", "tts")));

        var response = controller.degradationMatrix();

        assertThat(response.data()).hasSize(1);
    }

    @Test
    @DisplayName("POST /degradation/{point}/override → 手动切换（X-Confirm + reason）")
    void degradationOverride() {
        org.springframework.security.authentication.UsernamePasswordAuthenticationToken auth =
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("ops-1", null, List.of());
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            var response = controller.override("tts", "CONFIRM",
                    new OpsController.DegradationOverrideRequest("edge_tts", "主引擎故障切换"));

            assertThat(response.code()).isEqualTo(0);
            org.mockito.Mockito.verify(degradationMatrixService).override("tts", "edge_tts", "ops-1", "主引擎故障切换");
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    @DisplayName("POST /degradation/{point}/override/cancel → 取消覆盖")
    void degradationCancelOverride() {
        org.springframework.security.authentication.UsernamePasswordAuthenticationToken auth =
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("ops-1", null, List.of());
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            var response = controller.cancelOverride("tts", "CONFIRM", new OpsController.RiskCloseRequest("恢复默认"));

            assertThat(response.code()).isEqualTo(0);
            org.mockito.Mockito.verify(degradationMatrixService).cancelOverride("tts", "ops-1", "恢复默认");
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    @DisplayName("GET /degradation/events → 事件时间线（point 过滤 + limit）")
    void degradationEvents() {
        when(degradationMatrixService.events("tts", 50)).thenReturn(List.of());

        var response = controller.degradationEvents("tts", 50);

        assertThat(response.data()).isEmpty();
    }

    @Test
    @DisplayName("GET /knowledge/stats → 知识库平台统计")
    void knowledgeStats() {
        when(knowledgeBaseService.platformStats(isNull())).thenReturn(Map.of("total", 10));

        var response = controller.knowledgeStats(null);

        assertThat(response.data()).containsEntry("total", 10);
    }

    @Test
    @DisplayName("GET /insights/channel-stats → 渠道统计")
    void channelStats() {
        when(opsInsightsService.channelStats()).thenReturn(Map.of("total", 88));

        var response = controller.channelStats();

        assertThat(response.data()).containsEntry("total", 88);
    }

    @Test
    @DisplayName("GET /insights/dead-ledger → 脱敏台账（limit 透传）")
    void deadLedger() {
        when(opsInsightsService.deadLedger(50)).thenReturn(List.of());

        var response = controller.deadLedger(50);

        assertThat(response.data()).isEmpty();
    }

    @Test
    @DisplayName("GET /insights/quality-trend → 质量趋势")
    void qualityTrend() {
        when(opsInsightsService.qualityTrend()).thenReturn(Map.of("trend", 1));

        var response = controller.qualityTrend();

        assertThat(response.data()).containsEntry("trend", 1);
    }

    @Test
    @DisplayName("GET /insights/alert-funnel → 预警漏斗")
    void alertFunnel() {
        when(opsInsightsService.alertFunnel()).thenReturn(Map.of("detected", 100));

        var response = controller.alertFunnel();

        assertThat(response.data()).containsEntry("detected", 100);
    }

    @Test
    @DisplayName("GET /insights/tenant-health → 租户健康度")
    void tenantHealth() {
        when(opsInsightsService.tenantHealth()).thenReturn(List.of(Map.of("tenantId", "t1")));

        var response = controller.tenantHealth();

        assertThat(response.data()).hasSize(1);
    }

    @Test
    @DisplayName("GET /usage/summary → 用量报表（days 透传）")
    void usageSummary() {
        when(opsInsightsService.usageSummary(30)).thenReturn(Map.of("llm_call", 100));

        var response = controller.usageSummary(30);

        assertThat(response.data()).containsEntry("llm_call", 100);
    }

    @Test
    @DisplayName("GET /compliance/consent-stats → 合规视图")
    void consentStats() {
        when(opsInsightsService.consentStats()).thenReturn(Map.of("total", 42));

        var response = controller.consentStats();

        assertThat(response.data()).containsEntry("total", 42);
    }

    // ===== M2 指标看板 + 告警事件中心（ADMIN-P1-07/08） =====

    @Test
    @DisplayName("GET /metrics/query → 白名单表达式透传 MetricsQueryService")
    void metricsQuery() {
        when(metricsQueryService.query("tts_synthesize_requests_total"))
                .thenReturn(Map.of("status", "success"));

        var response = controller.metricsQuery("tts_synthesize_requests_total");

        assertThat(response.data()).containsEntry("status", "success");
    }

    @Test
    @DisplayName("GET /alert-events → alert_events 落库台账（status/limit 透传）")
    void alertEvents() {
        AlertEvent event = new AlertEvent();
        event.setEventId(UUID.randomUUID());
        event.setStatus(AlertEvent.STATUS_FIRING);
        when(opsService.alertEvents("firing", 100)).thenReturn(List.of(event));

        var response = controller.alertEvents("firing", 100);

        assertThat(response.data()).hasSize(1);
        assertThat(response.data().get(0).getStatus()).isEqualTo(AlertEvent.STATUS_FIRING);
    }

    @Test
    @DisplayName("POST /alerts/{id}/ack → X-Confirm 缺失被拒 / 合法透传 ack")
    void ackAlert() {
        UUID eventId = UUID.randomUUID();
        var request = new OpsController.RiskCloseRequest("处理完成");

        var rejected = controller.ackAlert(eventId, null, request);
        assertThat(rejected.code()).isNotEqualTo(0);

        var accepted = controller.ackAlert(eventId, "CONFIRM", request);
        assertThat(accepted.code()).isEqualTo(0);
        verify(opsService).ackAlert(eventId, "unknown");
    }
}
