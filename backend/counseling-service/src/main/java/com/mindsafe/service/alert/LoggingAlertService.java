package com.mindsafe.service.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

/**
 * 日志降级告警（OPS-004 兜底）
 * <p>
 * 当企微/钉钉 webhook 未配置时，告警仅写入日志。
 */
@Service
@ConditionalOnMissingBean(WeComAlertService.class)
public class LoggingAlertService implements AlertService {

    private static final Logger log = LoggerFactory.getLogger(LoggingAlertService.class);

    @Override
    public void sendAlert(AlertLevel level, String title, String detail) {
        switch (level) {
            case CRITICAL -> log.error("[ALERT-CRITICAL] {}: {}", title, detail);
            case WARNING -> log.warn("[ALERT-WARNING] {}: {}", title, detail);
            case INFO -> log.info("[ALERT-INFO] {}: {}", title, detail);
        }
    }
}
