package com.mindsafe.api.controller;

import com.mindsafe.domain.entity.AuditLog;
import com.mindsafe.domain.entity.ServiceHealthSnapshot;
import com.mindsafe.service.monitoring.OpsService;
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
import static org.mockito.Mockito.when;

/**
 * OpsController 单元测试（ADMIN-P0-05/06/07：服务拓扑/告警只读/审计跨租户查询 HTTP 编排）
 */
class OpsControllerTest {

    private OpsService opsService;
    private OpsController controller;

    @BeforeEach
    void setUp() {
        opsService = mock(OpsService.class);
        controller = new OpsController(opsService);
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
}
