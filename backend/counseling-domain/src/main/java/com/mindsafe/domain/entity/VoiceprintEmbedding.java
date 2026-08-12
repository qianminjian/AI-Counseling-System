package com.mindsafe.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.mindsafe.domain.typehandler.JsonbTypeHandler;

import java.time.Instant;
import java.util.UUID;

/**
 * 声纹 embedding 向量（remote 模式：前端提取后传服务端存储/比对）
 * 隐私：仅存 256-dim 特征向量，不存原始音频
 */
@TableName(value = "voiceprint_embeddings", schema = TenantSchema.TENANT_TEMPLATE, autoResultMap = true)
public class VoiceprintEmbedding {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private UUID userId;
    private UUID tenantId;

    /** 256-dim float 数组 JSON（JSONB 存储） */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String embedding;

    private Integer sampleIndex;
    private Instant createdAt;

    // ===== getters/setters =====

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public String getEmbedding() { return embedding; }
    public void setEmbedding(String embedding) { this.embedding = embedding; }

    public Integer getSampleIndex() { return sampleIndex; }
    public void setSampleIndex(Integer sampleIndex) { this.sampleIndex = sampleIndex; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
