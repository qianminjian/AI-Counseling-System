package com.mindsafe.service.notification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.mapper.RiskEventMapper;
import com.mindsafe.service.alert.AlertService;
import com.mindsafe.service.alert.AlertService.AlertLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 风险通知 outbox 补偿（P0-4）
 * <p>
 * 目标：RED/ORANGE 教师通知不允许静默丢失——事件落库后若通知失败，
 * 由本服务维护 notify_status 状态机并由补偿任务重试，超限转人工兜底。
 * <p>
 * 状态机：pending（落库未通知）→ sent / failed →（重试）→ sent / dead（超 5 次转人工）
 * <p>
 * 事务策略：所有状态更新独立 REQUIRES_NEW——通知失败时主事务（risk_event 落库）
 * 可能已标记 rollback-only，独立事务确保状态标记必然持久化，不受主事务回滚影响。
 */
@Service
public class RiskNotifyOutboxService {

    private static final Logger log = LoggerFactory.getLogger(RiskNotifyOutboxService.class);

    /** 通知尝试上限（超过转 dead 人工兜底） */
    public static final int MAX_NOTIFY_ATTEMPTS = 5;
    /** 补偿窗口：仅重试 24 小时内的风险事件（超出转 dead 由人工核对） */
    public static final int RETRY_WINDOW_HOURS = 24;

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_SENT = "sent";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_DEAD = "dead";

    private final RiskEventMapper riskEventMapper;
    private final NotificationService notificationService;
    private final AlertService alertService;

    public RiskNotifyOutboxService(RiskEventMapper riskEventMapper,
                                   NotificationService notificationService,
                                   AlertService alertService) {
        this.riskEventMapper = riskEventMapper;
        this.notificationService = notificationService;
        this.alertService = alertService;
    }

    /** 通知成功：标记 sent（独立事务） */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSent(RiskEvent event) {
        RiskEvent update = new RiskEvent();
        update.setRiskEventId(event.getRiskEventId());
        update.setNotifyStatus(STATUS_SENT);
        update.setNotifyAttempts((event.getNotifyAttempts() == null ? 0 : event.getNotifyAttempts()) + 1);
        update.setLastNotifyAttemptAt(Instant.now());
        riskEventMapper.updateById(update);
    }

    /** 通知失败：标记 failed（独立事务） */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(RiskEvent event) {
        RiskEvent update = new RiskEvent();
        update.setRiskEventId(event.getRiskEventId());
        update.setNotifyStatus(STATUS_FAILED);
        update.setNotifyAttempts((event.getNotifyAttempts() == null ? 0 : event.getNotifyAttempts()) + 1);
        update.setLastNotifyAttemptAt(Instant.now());
        riskEventMapper.updateById(update);
    }

    /** 超限放弃：标记 dead + 统一告警出口（BA-08 接入 AlertService，原仅 log.error） + 错误日志 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDead(RiskEvent event) {
        RiskEvent update = new RiskEvent();
        update.setRiskEventId(event.getRiskEventId());
        update.setNotifyStatus(STATUS_DEAD);
        update.setNotifyAttempts((event.getNotifyAttempts() == null ? 0 : event.getNotifyAttempts()) + 1);
        update.setLastNotifyAttemptAt(Instant.now());
        riskEventMapper.updateById(update);
        // 人工兜底信号走统一告警出口（企微 webhook 或日志降级，外呼失败不影响状态标记）
        alertService.sendAlert(AlertLevel.WARNING, "风险通知持续失败转人工兜底",
                "riskEventId=" + event.getRiskEventId()
                        + ", level=" + event.getRiskLevel()
                        + ", attempts=" + (event.getNotifyAttempts() == null ? 0 : event.getNotifyAttempts()));
        log.error("风险通知持续失败转人工兜底: riskEventId={}, level={}, attempts={}",
                event.getRiskEventId(), event.getRiskLevel(),
                event.getNotifyAttempts() == null ? 0 : event.getNotifyAttempts());
    }

    /**
     * 补偿重试：扫描 pending/failed 且未超限的事件，重新通知。
     * 由 {@code RiskNotifyRetryJob} 定时驱动，调用方须处于系统作用域（跨租户）。
     *
     * @return 本次成功重试的事件数
     */
    public int retryDueEvents() {
        List<RiskEvent> due = riskEventMapper.selectList(
                new LambdaQueryWrapper<RiskEvent>()
                        .in(RiskEvent::getNotifyStatus, List.of(STATUS_PENDING, STATUS_FAILED))
                        .ge(RiskEvent::getDetectedAt,
                                Instant.now().minusSeconds(RETRY_WINDOW_HOURS * 3600L))
        );

        int succeeded = 0;
        for (RiskEvent event : due) {
            int attempts = event.getNotifyAttempts() == null ? 0 : event.getNotifyAttempts();
            // 超限：转 dead 人工兜底，不再自动重试
            if (attempts >= MAX_NOTIFY_ATTEMPTS) {
                markDead(event);
                continue;
            }
            try {
                notificationService.notifyRiskEvent(event);
                markSent(event);
                succeeded++;
                log.info("风险通知补偿成功: riskEventId={}, attempts={}",
                        event.getRiskEventId(), attempts);
            } catch (Exception e) {
                markFailed(event);
                log.warn("风险通知补偿仍失败: riskEventId={}, attempts={}, err={}",
                        event.getRiskEventId(), attempts + 1, e.getMessage());
            }
        }
        return succeeded;
    }
}
