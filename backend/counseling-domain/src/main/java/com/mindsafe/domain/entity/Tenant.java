package com.mindsafe.domain.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.time.Instant;
import java.util.UUID;

/**
 * 租户实体（对应 public.tenants）
 */
@TableName(value = "tenants", schema = "public")
public class Tenant {

    @TableId(value = "tenant_id", type = IdType.INPUT)
    private UUID tenantId;

    private String tenantCode;
    private String tenantName;
    private String dataRegion;
    private String kmsKeyRef;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant deletedAt;

    // ===== Getters & Setters =====
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public String getTenantCode() { return tenantCode; }
    public void setTenantCode(String tenantCode) { this.tenantCode = tenantCode; }

    public String getTenantName() { return tenantName; }
    public void setTenantName(String tenantName) { this.tenantName = tenantName; }

    public String getDataRegion() { return dataRegion; }
    public void setDataRegion(String dataRegion) { this.dataRegion = dataRegion; }

    public String getKmsKeyRef() { return kmsKeyRef; }
    public void setKmsKeyRef(String kmsKeyRef) { this.kmsKeyRef = kmsKeyRef; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }
}
