package com.mindsafe.service.safety;

import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.mapper.RiskEventMapper;
import com.mindsafe.service.risk.RiskEventWriter;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * SOS 事件服务测试（P0-2 审计修复：design/36 §3.4「学生点击 SOS 1min 内产生 S2 事件」）
 */
@ExtendWith(MockitoExtension.class)
class SosEventServiceTest {

    @Mock
    private RiskEventMapper riskEventMapper;

    @Mock
    private RiskEventWriter riskEventWriter;

    private SosEventService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID studentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new SosEventService(riskEventMapper, riskEventWriter);
    }

    @Test
    @DisplayName("正常上报：落 S2(YELLOW) 风险事件 sourceType=sos 并经统一入口通知教师（write(event, true)）")
    void recordSosEvent_insertsS2Event_andNotifies() {
        when(riskEventMapper.selectCount(any())).thenReturn(0L);

        SosEventService.SosResult result = service.recordSosEvent(tenantId, studentId);

        assertThat(result.deduplicated()).isFalse();
        assertThat(result.riskEventId()).isNotNull();

        // S-009：落库 + 通知义务统一由 RiskEventWriter 承担（SOS 需教师通知 → true）
        ArgumentCaptor<RiskEvent> captor = ArgumentCaptor.forClass(RiskEvent.class);
        verify(riskEventWriter).write(captor.capture(), eq(true));
        RiskEvent event = captor.getValue();
        assertThat(event.getTenantId()).isEqualTo(tenantId);
        assertThat(event.getStudentUserId()).isEqualTo(studentId);
        assertThat(event.getSourceType()).isEqualTo("sos");
        assertThat(event.getSourceId()).isNull();
        assertThat(event.getRiskType()).isEqualTo("sos_open");
        assertThat(event.getRiskLevel()).isEqualTo(1); // S2 = YELLOW
        assertThat(event.getDetectedBy()).isEqualTo("sos_button");
        assertThat(event.getStatus()).isEqualTo("open");
    }

    @Test
    @DisplayName("5 分钟去重窗口：同一学生短时间重复点击不重复落事件、不重复通知")
    void recordSosEvent_dedupWithinWindow() {
        when(riskEventMapper.selectCount(any())).thenReturn(1L);

        SosEventService.SosResult result = service.recordSosEvent(tenantId, studentId);

        assertThat(result.deduplicated()).isTrue();
        verify(riskEventWriter, never()).write(any(RiskEvent.class), anyBoolean());
    }

    @Test
    @DisplayName("通知失败语义已收敛至 RiskEventWriter（P0-4：内部 catch + markFailed 进补偿队列），本层不阻断")
    void recordSosEvent_notificationFailure_swallowed() {
        when(riskEventMapper.selectCount(any())).thenReturn(0L);

        SosEventService.SosResult result = service.recordSosEvent(tenantId, studentId);

        assertThat(result.deduplicated()).isFalse();
        assertThat(result.riskEventId()).isNotNull();
        verify(riskEventWriter).write(any(RiskEvent.class), eq(true));
    }

    @Test
    @DisplayName("落库失败 fail-fast：安全关键记录不允许静默丢失")
    void recordSosEvent_insertFailure_throws() {
        when(riskEventMapper.selectCount(any())).thenReturn(0L);
        when(riskEventWriter.write(any(RiskEvent.class), anyBoolean()))
                .thenThrow(new RuntimeException("DB 不可用"));

        assertThatThrownBy(() -> service.recordSosEvent(tenantId, studentId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SOS");
    }
}
