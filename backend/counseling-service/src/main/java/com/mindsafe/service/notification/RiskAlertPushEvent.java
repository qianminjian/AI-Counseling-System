package com.mindsafe.service.notification;

import java.time.Instant;
import java.util.UUID;

/**
 * 风险预警事件（Spring ApplicationEvent）
 * 由 NotificationServiceImpl 发布，WebSocket 层监听并实时推送给在线教师
 */
public record RiskAlertPushEvent(
        UUID tenantId,
        UUID riskEventId,
        UUID studentUserId,
        UUID sessionId,
        String riskType,
        int riskLevel,
        String title,
        String body,
        Instant detectedAt
) {}
