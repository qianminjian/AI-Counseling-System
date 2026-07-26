package com.mindsafe.api.websocket;

import com.mindsafe.service.notification.RiskAlertPushEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 监听风险预警事件，通过 WebSocket 实时推送给在线教师
 */
@Component
public class AlertPushListener {

    private static final Logger log = LoggerFactory.getLogger(AlertPushListener.class);

    private final AlertWebSocketHandler webSocketHandler;

    public AlertPushListener(AlertWebSocketHandler webSocketHandler) {
        this.webSocketHandler = webSocketHandler;
    }

    @Async
    @EventListener
    public void onRiskAlert(RiskAlertPushEvent event) {
        int online = webSocketHandler.getOnlineCount(event.tenantId());
        if (online == 0) return;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "risk_alert");
        payload.put("riskEventId", event.riskEventId().toString());
        payload.put("studentUserId", event.studentUserId().toString());
        if (event.sessionId() != null) {
            payload.put("sessionId", event.sessionId().toString());
        }
        payload.put("riskType", event.riskType());
        payload.put("riskLevel", event.riskLevel());
        payload.put("title", event.title());
        payload.put("body", event.body());
        payload.put("detectedAt", event.detectedAt().toString());

        webSocketHandler.pushAlert(event.tenantId(), payload);
        log.debug("WebSocket 预警推送: tenant={}, level={}, online={}", event.tenantId(), event.riskLevel(), online);
    }
}
