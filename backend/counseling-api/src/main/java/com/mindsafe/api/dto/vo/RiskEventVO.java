package com.mindsafe.api.dto.vo;

import com.mindsafe.domain.entity.RiskEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * 风险事件 VO（F9：教师端风险事件列表响应，替代实体直接暴露）。
 * <p>
 * 字段语义与 {@link RiskEvent} 完全一致（仅包装层变化，契约不变），
 * 实体字段演进不再直接影响 API 契约。
 */
public record RiskEventVO(
        UUID riskEventId,
        UUID tenantId,
        UUID schoolId,
        UUID studentUserId,
        String sourceType,
        UUID sourceId,
        String riskType,
        Integer riskLevel,
        Integer riskScore,
        String reasonCodes,
        String reviewJson,
        String detectedBy,
        Instant detectedAt,
        String status,
        UUID assignedUserId,
        Instant closedAt,
        Instant createdAt,
        Instant updatedAt,
        String resolutionNote,
        Instant resolvedAt,
        Instant followUpAt,
        String followUpNote,
        Boolean followUpDone,
        String outcome,
        String notifyStatus,
        Integer notifyAttempts,
        Instant lastNotifyAttemptAt
) {
    public static RiskEventVO from(RiskEvent e) {
        return new RiskEventVO(
                e.getRiskEventId(), e.getTenantId(), e.getSchoolId(), e.getStudentUserId(),
                e.getSourceType(), e.getSourceId(), e.getRiskType(), e.getRiskLevel(), e.getRiskScore(),
                e.getReasonCodes(), e.getReviewJson(), e.getDetectedBy(), e.getDetectedAt(), e.getStatus(),
                e.getAssignedUserId(), e.getClosedAt(), e.getCreatedAt(), e.getUpdatedAt(),
                e.getResolutionNote(), e.getResolvedAt(), e.getFollowUpAt(), e.getFollowUpNote(),
                e.getFollowUpDone(), e.getOutcome(), e.getNotifyStatus(), e.getNotifyAttempts(),
                e.getLastNotifyAttemptAt());
    }
}
