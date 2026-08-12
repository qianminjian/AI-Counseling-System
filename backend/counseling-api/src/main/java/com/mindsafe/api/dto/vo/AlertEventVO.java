package com.mindsafe.api.dto.vo;

import com.mindsafe.domain.entity.AlertEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * 告警事件 VO（F9：ops 告警事件历史响应，替代实体直接暴露）。
 */
public record AlertEventVO(
        UUID eventId,
        String source,
        String fingerprint,
        String ruleName,
        String severity,
        String status,
        String summary,
        String detail,
        String acknowledgedBy,
        Instant acknowledgedAt,
        String ackReason,
        Instant firedAt,
        Instant resolvedAt,
        Instant createdAt,
        String notifyStatus
) {
    public static AlertEventVO from(AlertEvent e) {
        return new AlertEventVO(e.getEventId(), e.getSource(), e.getFingerprint(), e.getRuleName(),
                e.getSeverity(), e.getStatus(), e.getSummary(), e.getDetail(), e.getAcknowledgedBy(),
                e.getAcknowledgedAt(), e.getAckReason(), e.getFiredAt(), e.getResolvedAt(),
                e.getCreatedAt(), e.getNotifyStatus());
    }
}
