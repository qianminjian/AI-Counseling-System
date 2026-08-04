package com.mindsafe.service.notification;

import com.mindsafe.common.tenant.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 风险通知补偿任务（P0-4）
 * <p>
 * 每 2 分钟扫描 pending/failed 状态的风险事件，重新触发教师通知；
 * 超 5 次或超 24h 转 dead 由人工兜底。全租户扫描须显式系统作用域（M1-003）。
 */
@Component
public class RiskNotifyRetryJob {

    private static final Logger log = LoggerFactory.getLogger(RiskNotifyRetryJob.class);

    private final RiskNotifyOutboxService outboxService;

    @Value("${mindsafe.security.risk-notify-retry.enabled:true}")
    private boolean enabled;

    public RiskNotifyRetryJob(RiskNotifyOutboxService outboxService) {
        this.outboxService = outboxService;
    }

    @Scheduled(cron = "${mindsafe.security.risk-notify-retry.scan-cron:0 */2 * * * ?}")
    public void scan() {
        if (!enabled) {
            return;
        }
        try {
            // 全租户扫描属合法跨租户链路：显式声明系统作用域（M1-003 fail-fast 配套）
            int succeeded = TenantContextHolder.callAsSystem(outboxService::retryDueEvents);
            if (succeeded > 0) {
                log.info("风险通知补偿完成: 成功重试 {} 条", succeeded);
            }
        } catch (Exception e) {
            log.error("风险通知补偿扫描失败", e);
        }
    }
}
