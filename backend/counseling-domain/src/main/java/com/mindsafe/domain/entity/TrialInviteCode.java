package com.mindsafe.domain.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.time.Instant;
import java.util.UUID;

/**
 * 试用邀请码（对应 tenant_template.trial_invite_codes）
 */
@TableName(value = "trial_invite_codes", schema = "tenant_template")
public class TrialInviteCode {

    @TableId(value = "code_id", type = IdType.INPUT)
    private UUID codeId;

    private UUID tenantId;
    private String code;
    private Integer maxUses;
    private Integer usedCount;
    private Instant expiresAt;
    private String status;
    private UUID createdBy;
    private Instant createdAt;

    /** 绑定的用户ID（一人一码，用后填入） */
    private UUID boundUserId;
    /** 实际使用时间 */
    private Instant usedAt;
    /** 批次号（教师批量生成） */
    private String batchId;
    /** 生成者（教师 userId） */
    private UUID generatedBy;

    /** 校验邀请码是否可用（有效 + 未过期 + 未超限 + 未绑定） */
    public boolean isUsable() {
        if (!"active".equals(status)) return false;
        if (expiresAt != null && Instant.now().isAfter(expiresAt)) return false;
        if (boundUserId != null) return false; // 已绑定 = 已使用
        return usedCount == null || maxUses == null || usedCount < maxUses;
    }

    // ===== Getters & Setters =====

    public UUID getCodeId() { return codeId; }
    public void setCodeId(UUID codeId) { this.codeId = codeId; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public Integer getMaxUses() { return maxUses; }
    public void setMaxUses(Integer maxUses) { this.maxUses = maxUses; }

    public Integer getUsedCount() { return usedCount; }
    public void setUsedCount(Integer usedCount) { this.usedCount = usedCount; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public UUID getBoundUserId() { return boundUserId; }
    public void setBoundUserId(UUID boundUserId) { this.boundUserId = boundUserId; }

    public Instant getUsedAt() { return usedAt; }
    public void setUsedAt(Instant usedAt) { this.usedAt = usedAt; }

    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }

    public UUID getGeneratedBy() { return generatedBy; }
    public void setGeneratedBy(UUID generatedBy) { this.generatedBy = generatedBy; }
}
