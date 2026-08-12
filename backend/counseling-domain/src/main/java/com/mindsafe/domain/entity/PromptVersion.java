package com.mindsafe.domain.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Prompt 版本实体（AI-005：版本管理 + A/B 测试分组）
 */
@TableName(value = "prompt_versions", schema = TenantSchema.TENANT_TEMPLATE)
public class PromptVersion {

    /** M7 审核发布流状态（V36）：草稿 */
    public static final String STATUS_DRAFT = "draft";

    /** M7 审核发布流状态（V36）：待审核 */
    public static final String STATUS_PENDING_REVIEW = "pending_review";

    /** M7 审核发布流状态（V36）：已审核通过（可激活） */
    public static final String STATUS_APPROVED = "approved";

    /** M7 审核发布流状态（V36）：已激活生效 */
    public static final String STATUS_ACTIVE = "active";

    /** M7 审核发布流状态（V36）：已停用 */
    public static final String STATUS_RETIRED = "retired";


    @TableId(value = "version_id", type = IdType.INPUT)
    private UUID versionId;

    /** NULL = 全局默认（平台级），非 NULL = 租户定制 */
    private UUID tenantId;

    /** 模板标识，如 SYS_001, SKL_001 */
    private String templateKey;

    /** 递增版本号 */
    private Integer version;

    /** Prompt 模板内容（含 {{var}} 占位符） */
    private String content;

    /** 版本变更说明 */
    private String description;

    /** A/B 分组: control / treatment_a / treatment_b */
    private String abGroup;

    /** 是否为当前生效版本 */
    private Boolean isActive;

    /** M7 审核发布流状态（V36）：draft/pending_review/approved/active/retired（is_active 保留兼容，激活时两者同步） */
    private String status;
    private UUID createdBy;
    private Instant createdAt;
    private Instant updatedAt;

    public PromptVersion() {
    }

    public static PromptVersion create(UUID tenantId, String templateKey, int version,
                                       String content, String description, String abGroup, UUID createdBy) {
        PromptVersion pv = new PromptVersion();
        pv.versionId = UUID.randomUUID();
        pv.tenantId = tenantId;
        pv.templateKey = templateKey;
        pv.version = version;
        pv.content = content;
        pv.description = description;
        pv.abGroup = abGroup != null ? abGroup : "control";
        pv.isActive = false;
        pv.createdBy = createdBy;
        pv.createdAt = Instant.now();
        pv.updatedAt = Instant.now();
        return pv;
    }

    /** 生成版本标识（用于会话记录）：SYS_001:v3:treatment_a */
    public String versionTag() {
        return templateKey + ":v" + version + ":" + abGroup;
    }

    // ===== Getters & Setters =====

    public UUID getVersionId() { return versionId; }
    public void setVersionId(UUID versionId) { this.versionId = versionId; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public String getTemplateKey() { return templateKey; }
    public void setTemplateKey(String templateKey) { this.templateKey = templateKey; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getAbGroup() { return abGroup; }
    public void setAbGroup(String abGroup) { this.abGroup = abGroup; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
