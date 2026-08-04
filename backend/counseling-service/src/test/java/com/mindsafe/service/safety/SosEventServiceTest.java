package com.mindsafe.service.safety;

import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.mapper.RiskEventMapper;
import com.mindsafe.service.notification.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * SOS 事件服务测试（P0-2 审计修复：design/36 §3.4「学生点击 SOS 1min 内产生 S2 事件」）
 */
@ExtendWith(MockitoExtension.class)
class SosEventServiceTest {

    @Mock
    private RiskEventMapper riskEventMapper;

    @Mock
    private NotificationService notificationService;

    private SosEventService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID studentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new SosEventService(riskEventMapper, notificationService);
    }

    @Test
    @DisplayName("正常上报：落 S2(YELLOW) 风险事件 sourceType=sos 并通知教师")
    void recordSosEvent_insertsS2Event_andNotifies() {
        when(riskEventMapper.selectCount(any())).thenReturn(0L);
        when(riskEventMapper.insert(any(RiskEvent.class))).thenReturn(1);

        SosEventService.SosResult result = service.recordSosEvent(tenantId, studentId);

        assertThat(result.deduplicated()).isFalse();
        assertThat(result.riskEventId()).isNotNull();

        ArgumentCaptor<RiskEvent> captor = ArgumentCaptor.forClass(RiskEvent.class);
        verify(riskEventMapper).insert(captor.capture());
        RiskEvent event = captor.getValue();
        assertThat(event.getTenantId()).isEqualTo(tenantId);
        assertThat(event.getStudentUserId()).isEqualTo(studentId);
        assertThat(event.getSourceType()).isEqualTo("sos");
        assertThat(event.getSourceId()).isNull();
        assertThat(event.getRiskType()).isEqualTo("sos_open");
        assertThat(event.getRiskLevel()).isEqualTo(1); // S2 = YELLOW
        assertThat(event.getDetectedBy()).isEqualTo("sos_button");
        assertThat(event.getStatus()).isEqualTo("open");

        verify(notificationService).notifyRiskEvent(event);
    }

    @Test
    @DisplayName("5 分钟去重窗口：同一学生短时间重复点击不重复落事件、不重复通知")
    void recordSosEvent_dedupWithinWindow() {
        when(riskEventMapper.selectCount(any())).thenReturn(1L);

        SosEventService.SosResult result = service.recordSosEvent(tenantId, studentId);

        assertThat(result.deduplicated()).isTrue();
        verify(riskEventMapper, never()).insert(any(RiskEvent.class));
        verify(notificationService, never()).notifyRiskEvent(any());
    }

    @Test
    @DisplayName("通知失败不阻断：事件已落库，SOS 链路对教师通知尽力而为")
    void recordSosEvent_notificationFailure_swallowed() {
        when(riskEventMapper.selectCount(any())).thenReturn(0L);
        when(riskEventMapper.insert(any(RiskEvent.class))).thenReturn(1);
        doThrow(new RuntimeException("通知服务宕机")).when(notificationService).notifyRiskEvent(any());

        SosEventService.SosResult result = service.recordSosEvent(tenantId, studentId);

        assertThat(result.deduplicated()).isFalse();
        assertThat(result.riskEventId()).isNotNull();
        verify(riskEventMapper).insert(any(RiskEvent.class));
    }

    @Test
    @DisplayName("落库失败 fail-fast：安全关键记录不允许静默丢失")
    void recordSosEvent_insertFailure_throws() {
        when(riskEventMapper.selectCount(any())).thenReturn(0L);
        when(riskEventMapper.insert(any(RiskEvent.class)))
                .thenThrow(new RuntimeException("DB 不可用"));

        assertThatThrownBy(() -> service.recordSosEvent(tenantId, studentId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SOS");
    }
}
