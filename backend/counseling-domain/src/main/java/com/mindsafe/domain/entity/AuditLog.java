package com.mindsafe.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.mindsafe.domain.typehandler.JsonbTypeHandler;

import java.time.Instant;
import java.util.UUID;

/**
 * 审计日志实体（对应 tenant_template.audit_logs）
 */
@TableName(value = "audit_logs", schema = TenantSchema.TENANT_TEMPLATE, autoResultMap = true)
public class AuditLog {

    @TableId(value = "audit_log_id", type = IdType.INPUT)
    private UUID auditLogId;

    private UUID tenantId;
    private UUID userId;
    private String action;
    private String resourceType;
    private UUID resourceId;
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String detail;
    private String ipHash;
    private String userAgent;
    private Instant createdAt;

    public AuditLog() {}

    public static AuditLog create(UUID tenantId, UUID userId, String action,
                                  String resourceType, UUID resourceId, String detail) {
        AuditLog log = new AuditLog();
        log.auditLogId = UUID.randomUUID();
        log.tenantId = tenantId;
        log.userId = userId;
        log.action = action;
        log.resourceType = resourceType;
        log.resourceId = resourceId;
        log.detail = detail;
        log.createdAt = Instant.now();
        return log;
    }

    // ===== Getters & Setters =====
    public UUID getAuditLogId() { return auditLogId; }
    public void setAuditLogId(UUID auditLogId) { this.auditLogId = auditLogId; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }

    public UUID getResourceId() { return resourceId; }
    public void setResourceId(UUID resourceId) { this.resourceId = resourceId; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }

    public String getIpHash() { return ipHash; }
    public void setIpHash(String ipHash) { this.ipHash = ipHash; }

    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
