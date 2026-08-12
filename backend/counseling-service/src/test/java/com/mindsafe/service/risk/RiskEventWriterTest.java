package com.mindsafe.service.risk;

import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.mapper.RiskEventMapper;
import com.mindsafe.service.notification.NotificationService;
import com.mindsafe.service.notification.RiskNotifyOutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * RiskEventWriter 单元测试（doing/93 S-009：风险事件写入统一入口）。
 * <p>
 * 覆盖：落库 + 通知义务登记——需通知（成功 sent / 失败 failed 进补偿队列）、
 * 无需通知（直接标记完成态防补偿误重试）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("风险事件写入统一入口（S-009）")
class RiskEventWriterTest {

    @Mock private RiskEventMapper riskEventMapper;
    @Mock private NotificationService notificationService;
    @Mock private RiskNotifyOutboxService riskNotifyOutboxService;

    private RiskEventWriter writer;

    @BeforeEach
    void setUp() {
        writer = new RiskEventWriter(riskEventMapper, notificationService, riskNotifyOutboxService);
    }

    private RiskEvent event() {
        RiskEvent e = new RiskEvent();
        e.setRiskEventId(java.util.UUID.randomUUID());
        return e;
    }

    @Test
    @DisplayName("needsNotify=true：落库 + 通知成功 → markSent")
    void write_needsNotify_success() {
        RiskEvent e = event();
        writer.write(e, true);

        verify(riskEventMapper).insert(e);
        verify(notificationService).notifyRiskEvent(e);
        verify(riskNotifyOutboxService).markSent(e);
    }

    @Test
    @DisplayName("needsNotify=true 通知失败 → markFailed 进补偿队列（不抛出）")
    void write_needsNotify_failureMarksFailed() {
        RiskEvent e = event();
        doThrow(new RuntimeException("企业微信不可用"))
                .when(notificationService).notifyRiskEvent(any(RiskEvent.class));

        writer.write(e, true);

        verify(riskEventMapper).insert(e);
        verify(riskNotifyOutboxService).markFailed(e);
        verify(riskNotifyOutboxService, never()).markSent(e);
    }

    @Test
    @DisplayName("needsNotify=false：落库 + 直接标记完成态（防补偿任务误重试留痕事件）")
    void write_noNotify_marksSentDirectly() {
        RiskEvent e = event();
        writer.write(e, false);

        verify(riskEventMapper).insert(e);
        verify(notificationService, never()).notifyRiskEvent(any(RiskEvent.class));
        verify(riskNotifyOutboxService).markSent(e);
    }
}
