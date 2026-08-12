package com.mindsafe.api.dto.vo;

import com.mindsafe.domain.entity.AuditLog;

import java.time.Instant;
import java.util.UUID;

/**
 * 审计日志 VO（F9：ops 审计日志查询响应，替代实体直接暴露）。
 */
public record AuditLogVO(
        UUID auditLogId,
        UUID tenantId,
        UUID userId,
        String action,
        String resourceType,
        UUID resourceId,
        String detail,
        String ipHash,
        String userAgent,
        Instant createdAt
) {
    public static AuditLogVO from(AuditLog a) {
        return new AuditLogVO(a.getAuditLogId(), a.getTenantId(), a.getUserId(), a.getAction(),
                a.getResourceType(), a.getResourceId(), a.getDetail(), a.getIpHash(), a.getUserAgent(),
                a.getCreatedAt());
    }
}
