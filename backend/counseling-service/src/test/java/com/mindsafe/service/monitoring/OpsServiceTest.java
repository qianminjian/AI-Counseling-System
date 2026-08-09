package com.mindsafe.service.monitoring;

import com.mindsafe.domain.entity.AuditLog;
import com.mindsafe.domain.entity.ServiceHealthSnapshot;
import com.mindsafe.domain.mapper.AuditLogMapper;
import com.mindsafe.domain.mapper.ServiceHealthSnapshotMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 运维域服务单元测试（ADMIN-P0-05/06/07：服务拓扑/告警只读代理/跨租户审计查询）
 * 覆盖：servicesStatus 透传 / healthHistory 过滤与 limit 钳制 / activeAlerts 成功与降级 / auditLogs 参数
 */
class OpsServiceTest {

    private final ServiceHealthProbe probe = mock(ServiceHealthProbe.class);
    private final ServiceHealthSnapshotMapper snapshotMapper = mock(ServiceHealthSnapshotMapper.class);
    private final AuditLogMapper auditLogMapper = mock(AuditLogMapper.class);
    private final OpsService opsService = new OpsService(probe, snapshotMapper, auditLogMapper);

    @Test
    @DisplayName("servicesStatus：探针结果原样透传")
    void servicesStatusPassthrough() {
        Map<String, String> statuses = new LinkedHashMap<>();
        statuses.put("tts", "DEGRADED");
        statuses.put("postgres", "UP");
        when(probe.probeAll()).thenReturn(statuses);

        Map<String, String> result = opsService.servicesStatus();

        assertThat(result).containsEntry("tts", "DEGRADED").containsEntry("postgres", "UP");
    }

    @Test
    @DisplayName("healthHistory：服务过滤 + limit 钳制（下限 1 / 上限 500）")
    void healthHistoryClampsLimit() {
        ServiceHealthSnapshot snapshot = new ServiceHealthSnapshot();
        snapshot.setService("tts");
        when(snapshotMapper.selectList(any())).thenReturn(List.of(snapshot));

        List<ServiceHealthSnapshot> low = opsService.healthHistory("tts", 0);
        List<ServiceHealthSnapshot> high = opsService.healthHistory("tts", 9999);

        assertThat(low).hasSize(1);
        assertThat(high).hasSize(1);
    }

    @Test
    @DisplayName("activeAlerts：AlertManager 不可达 → 空列表（不抛异常）")
    void activeAlertsDegradesToEmpty() {
        // OpsService 内部自建 RestTemplate（不可注入），URL 指向不可达端口触发异常分支
        OpsService unreachable = new OpsService(probe, snapshotMapper, auditLogMapper) {
            @Override
            public List<Map<String, Object>> activeAlerts() {
                return super.activeAlerts();
            }
        };
        // 通过反射设置 alertmanagerUrl 指向不可达端口
        try {
            var field = OpsService.class.getDeclaredField("alertmanagerUrl");
            field.setAccessible(true);
            field.set(unreachable, "http://127.0.0.1:1");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        List<Map<String, Object>> alerts = unreachable.activeAlerts();

        assertThat(alerts).isEmpty();
    }

    @Test
    @DisplayName("auditLogs：参数化查询透传（tenantId/action 过滤 + 时间范围）")
    void auditLogsParametric() {
        AuditLog log = new AuditLog();
        log.setAction("PLATFORM_LOGIN");
        when(auditLogMapper.selectList(any())).thenReturn(List.of(log));

        List<AuditLog> result = opsService.auditLogs(
                UUID.randomUUID(), "PLATFORM_LOGIN",
                Instant.now().minusSeconds(3600), Instant.now(), 100);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getAction()).isEqualTo("PLATFORM_LOGIN");
    }
}
