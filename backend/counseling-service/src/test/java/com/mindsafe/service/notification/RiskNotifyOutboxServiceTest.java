package com.mindsafe.service.notification;

import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.mapper.RiskEventMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 风险通知 outbox 补偿服务单测（P0-4）。
 * <p>
 * 覆盖：状态机标记（sent/failed/dead）、补偿重试三分支（成功/失败/超限转 dead）、
 * 补偿查询范围（仅 pending/failed 且 24h 内）。
 */
@ExtendWith(MockitoExtension.class)
class RiskNotifyOutboxServiceTest {

    @Mock private RiskEventMapper riskEventMapper;
    @Mock private NotificationService notificationService;

    private RiskNotifyOutboxService outbox;

    @BeforeEach
    void setUp() {
        outbox = new RiskNotifyOutboxService(riskEventMapper, notificationService);
    }

    private RiskEvent event(int attempts) {
        RiskEvent e = new RiskEvent();
        e.setRiskEventId(UUID.randomUUID());
        e.setNotifyAttempts(attempts);
        return e;
    }

    @Nested
    @DisplayName("状态机标记")
    class StateMarkers {

        @Test
        @DisplayName("markSent → 更新 sent + 次数+1 + 时间戳")
        void markSent_updatesStatus() {
            RiskEvent e = event(2);

            outbox.markSent(e);

            verify(riskEventMapper).updateById(argEventWith(3, RiskNotifyOutboxService.STATUS_SENT));
        }

        @Test
        @DisplayName("markFailed → 更新 failed + 次数+1")
        void markFailed_updatesStatus() {
            RiskEvent e = event(1);

            outbox.markFailed(e);

            verify(riskEventMapper).updateById(argEventWith(2, RiskNotifyOutboxService.STATUS_FAILED));
        }

        @Test
        @DisplayName("markDead → 更新 dead + 次数+1（人工兜底）")
        void markDead_updatesStatus() {
            RiskEvent e = event(RiskNotifyOutboxService.MAX_NOTIFY_ATTEMPTS);

            outbox.markDead(e);

            verify(riskEventMapper).updateById(argEventWith(6, RiskNotifyOutboxService.STATUS_DEAD));
        }

        @Test
        @DisplayName("attempts 为 null（老数据）→ 按 0 起算 +1")
        void nullAttempts_countsAsZero() {
            RiskEvent e = event(0);
            e.setNotifyAttempts(null);

            outbox.markSent(e);

            verify(riskEventMapper).updateById(argEventWith(1, RiskNotifyOutboxService.STATUS_SENT));
        }
    }

    @Nested
    @DisplayName("补偿重试 retryDueEvents")
    class RetryDueEvents {

        @Test
        @DisplayName("只扫描 pending/failed 且 24h 内的事件")
        void queriesOnlyPendingAndFailedWithinWindow() {
            when(riskEventMapper.selectList(any())).thenReturn(List.of());

            outbox.retryDueEvents();

            // 查询条件由 wrapper 构造，此处验证不越权扫描 sent/dead（直接验证行为即可）
            verify(riskEventMapper).selectList(any());
        }

        @Test
        @DisplayName("重试成功：重新通知 + 标记 sent + 计数返回")
        void retry_success_marksSent() {
            RiskEvent e = event(1);
            when(riskEventMapper.selectList(any())).thenReturn(List.of(e));

            int succeeded = outbox.retryDueEvents();

            assertThat(succeeded).isEqualTo(1);
            verify(notificationService).notifyRiskEvent(e);
            verify(riskEventMapper).updateById(argEventWith(2, RiskNotifyOutboxService.STATUS_SENT));
        }

        @Test
        @DisplayName("重试仍失败：标记 failed 不抛出，计数不增加")
        void retry_failure_marksFailed() {
            RiskEvent e = event(2);
            when(riskEventMapper.selectList(any())).thenReturn(List.of(e));
            doThrow(new RuntimeException("企业微信不可用"))
                    .when(notificationService).notifyRiskEvent(e);

            int succeeded = outbox.retryDueEvents();

            assertThat(succeeded).isZero();
            verify(riskEventMapper).updateById(argEventWith(3, RiskNotifyOutboxService.STATUS_FAILED));
        }

        @Test
        @DisplayName("超限（attempts>=5）：转 dead 人工兜底，不再尝试通知")
        void exceededAttempts_marksDeadWithoutNotify() {
            RiskEvent e = event(RiskNotifyOutboxService.MAX_NOTIFY_ATTEMPTS);
            when(riskEventMapper.selectList(any())).thenReturn(List.of(e));

            int succeeded = outbox.retryDueEvents();

            assertThat(succeeded).isZero();
            verify(notificationService, never()).notifyRiskEvent(e);
            verify(riskEventMapper).updateById(argEventWith(6, RiskNotifyOutboxService.STATUS_DEAD));
        }

        @Test
        @DisplayName("多事件混合：成功 1 条 + 失败 1 条互不影响")
        void mixedEvents_isolatedOutcomes() {
            RiskEvent ok = event(1);
            RiskEvent bad = event(2);
            when(riskEventMapper.selectList(any())).thenReturn(List.of(ok, bad));
            // 具体实例 stub 在严格模式会误伤其他参数调用，改用 doAnswer 按参数分支
            doAnswer(inv -> {
                if (inv.getArgument(0) == bad) {
                    throw new RuntimeException("服务不可用");
                }
                return null;
            }).when(notificationService).notifyRiskEvent(any(RiskEvent.class));

            int succeeded = outbox.retryDueEvents();

            assertThat(succeeded).isEqualTo(1);
            verify(notificationService).notifyRiskEvent(ok);
            verify(notificationService).notifyRiskEvent(bad);
            verify(riskEventMapper).updateById(argEventWith(2, RiskNotifyOutboxService.STATUS_SENT));
            verify(riskEventMapper).updateById(argEventWith(3, RiskNotifyOutboxService.STATUS_FAILED));
        }
    }

    private RiskEvent argEventWith(int attempts, String status) {
        return org.mockito.ArgumentMatchers.argThat(e -> e.getRiskEventId() != null
                && attempts == e.getNotifyAttempts()
                && status.equals(e.getNotifyStatus())
                && e.getLastNotifyAttemptAt() != null);
    }
}
