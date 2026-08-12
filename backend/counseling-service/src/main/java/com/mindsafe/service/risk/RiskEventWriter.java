package com.mindsafe.service.risk;

import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.mapper.RiskEventMapper;
import com.mindsafe.service.notification.NotificationService;
import com.mindsafe.service.notification.RiskNotifyOutboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 风险事件写入统一入口（S-009，doing/93）。
 * <p>
 * "构造 RiskEvent + 登记通知义务"收敛于此：writer 只描述"这是什么风险、要不要通知"，
 * 落库与义务标记（需通知→notify+outbox；无需→直接标记完成态防补偿误重试）由本入口统一承担。
 * 此前两个 writer（会话风险 fromDetection 工厂 + 趋势关注手工 setter）各写半套 ，
 * retryDueEvents 无法区分"本就无需通知"与"通知失败"；新 writer（如量表熔断，frozen/34 S-004 关联）
 * 不再背负"记得 markSent"的隐性契约。
 * <p>
 * OPS-P3-09（doing/96）事务语义声明：本类不持 @Transactional——事件插入自动提交，
 * 通知走 outbox（REQUIRES_NEW）异步，"事件先行、通知异步"为本项目既定语义，孤儿记录风险低；
 * 若未来需与主链路同事务落库，请在调用方（而非本类）开启事务。
 */
@Service
public class RiskEventWriter {

    private static final Logger log = LoggerFactory.getLogger(RiskEventWriter.class);

    private final RiskEventMapper riskEventMapper;
    private final NotificationService notificationService;
    private final RiskNotifyOutboxService riskNotifyOutboxService;

    public RiskEventWriter(RiskEventMapper riskEventMapper,
                           NotificationService notificationService,
                           RiskNotifyOutboxService riskNotifyOutboxService) {
        this.riskEventMapper = riskEventMapper;
        this.notificationService = notificationService;
        this.riskNotifyOutboxService = riskNotifyOutboxService;
    }

    /**
     * 落库并登记通知义务。
     *
     * @param event       已构造的风险事件（调用方负责字段拼装）
     * @param needsNotify 是否需教师通知（false → 直接标记完成态，防补偿任务误重试留痕事件）
     * @return 落库后的事件
     */
    public RiskEvent write(RiskEvent event, boolean needsNotify) {
        riskEventMapper.insert(event);
        if (needsNotify) {
            try {
                notificationService.notifyRiskEvent(event);
                riskNotifyOutboxService.markSent(event);
            } catch (Exception e) {
                log.error("风险教师通知失败(已标记 failed 进补偿队列): riskEventId={}", event.getRiskEventId(), e);
                riskNotifyOutboxService.markFailed(event);
            }
        } else {
            // P0-4：无通知义务的事件标记完成态，防止补偿任务误重试留痕事件
            riskNotifyOutboxService.markSent(event);
        }
        return event;
    }
}
