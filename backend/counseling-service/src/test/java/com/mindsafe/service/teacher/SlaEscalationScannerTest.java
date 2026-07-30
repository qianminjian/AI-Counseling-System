package com.mindsafe.service.teacher;

import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.mapper.RiskEventMapper;
import com.mindsafe.service.alert.AlertService;
import com.mindsafe.service.alert.AlertService.AlertLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * P-05 SLA 超时兜底扫描 单元测试
 * <p>
 * 使用真实 {@link AlertSlaPolicy}（纯函数），mock RiskEventMapper 与 AlertService。
 */
class SlaEscalationScannerTest {

    private RiskEventMapper riskEventMapper;
    private AlertService alertService;
    private SlaEscalationScanner scanner;

    @BeforeEach
    void setUp() {
        riskEventMapper = mock(RiskEventMapper.class);
        alertService = mock(AlertService.class);
        scanner = new SlaEscalationScanner(riskEventMapper, new AlertSlaPolicy(), alertService, true, 30);
    }

    private RiskEvent event(int riskLevel, String status, int ageMinutes) {
        RiskEvent e = new RiskEvent();
        e.setRiskEventId(UUID.randomUUID());
        e.setTenantId(UUID.randomUUID());
        e.setStudentUserId(UUID.randomUUID());
        e.setRiskType("suicide_ideation");
        e.setRiskLevel(riskLevel);
        e.setStatus(status);
        e.setCreatedAt(Instant.now().minus(ageMinutes, ChronoUnit.MINUTES));
        return e;
    }

    @Test
    @DisplayName("红色 open 超 5 分钟 → CRITICAL 升级告警")
    void redOpenOverdue_escalatesCritical() {
        when(riskEventMapper.selectList(any())).thenReturn(List.of(event(3, "open", 6)));

        scanner.scan();

        verify(alertService).sendAlert(eq(AlertLevel.CRITICAL), anyString(), anyString());
    }

    @Test
    @DisplayName("红色 claimed 超时 → WARNING 提醒告警（认领未处理完）")
    void redClaimedOverdue_remindsWarning() {
        when(riskEventMapper.selectList(any())).thenReturn(List.of(event(3, "claimed", 10)));

        scanner.scan();

        verify(alertService).sendAlert(eq(AlertLevel.WARNING), anyString(), anyString());
    }

    @Test
    @DisplayName("红色 open 未超 5 分钟 → 不告警")
    void redOpenWithinSla_noAlert() {
        when(riskEventMapper.selectList(any())).thenReturn(List.of(event(3, "open", 3)));

        scanner.scan();

        verifyNoInteractions(alertService);
    }

    @Test
    @DisplayName("冷却期内重复扫描同一事件 → 只告警一次")
    void dedupWithinCooldown_alertsOnce() {
        List<RiskEvent> same = List.of(event(3, "open", 6));
        when(riskEventMapper.selectList(any())).thenReturn(same);

        scanner.scan();
        scanner.scan();

        verify(alertService, times(1)).sendAlert(eq(AlertLevel.CRITICAL), anyString(), anyString());
    }

    @Test
    @DisplayName("关闭时 enabled=false → 不扫描不告警")
    void disabled_skipsScan() {
        SlaEscalationScanner disabled =
                new SlaEscalationScanner(riskEventMapper, new AlertSlaPolicy(), alertService, false, 30);

        disabled.scan();

        verifyNoInteractions(riskEventMapper);
        verifyNoInteractions(alertService);
    }

    @Test
    @DisplayName("单次异常不抛出（定时任务不中断）")
    void mapperException_swallowed() {
        when(riskEventMapper.selectList(any())).thenThrow(new RuntimeException("db down"));

        scanner.scan(); // 不应抛出

        verify(alertService, never()).sendAlert(any(), anyString(), anyString());
    }
}
