package com.mindsafe.domain.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.time.Instant;
import java.util.UUID;

/**
 * 长期记忆实体（AI-008：跨会话关键事件 + 主题记忆）
 */
@TableName(value = "long_term_memories", schema = "tenant_template")
public class LongTermMemory {

    @TableId(value = "memory_id", type = IdType.INPUT)
    private UUID memoryId;

    private UUID tenantId;
    private UUID studentUserId;
    private UUID sessionId;

    /** key_event / recurring_theme */
    private String memoryType;

    /** 记忆内容（泛化描述，不含真实姓名/地名） */
    private String content;

    /** 情绪上下文标签 */
    private String emotionContext;

    /** 重要性 0.0~1.0 */
    private Float importance;

    /** 被回注次数 */
    private Integer recallCount;

    private Instant lastRecalledAt;
    private Instant createdAt;
    private Instant updatedAt;

    public LongTermMemory() {
    }

    public static LongTermMemory keyEvent(UUID tenantId, UUID studentUserId, UUID sessionId,
                                          String content, String emotionContext, float importance) {
        LongTermMemory m = new LongTermMemory();
        m.memoryId = UUID.randomUUID();
        m.tenantId = tenantId;
        m.studentUserId = studentUserId;
        m.sessionId = sessionId;
        m.memoryType = "key_event";
        m.content = content;
        m.emotionContext = emotionContext;
        m.importance = importance;
        m.recallCount = 0;
        m.createdAt = Instant.now();
        m.updatedAt = Instant.now();
        return m;
    }

    // ===== Getters & Setters =====

    public UUID getMemoryId() { return memoryId; }
    public void setMemoryId(UUID memoryId) { this.memoryId = memoryId; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public UUID getStudentUserId() { return studentUserId; }
    public void setStudentUserId(UUID studentUserId) { this.studentUserId = studentUserId; }

    public UUID getSessionId() { return sessionId; }
    public void setSessionId(UUID sessionId) { this.sessionId = sessionId; }

    public String getMemoryType() { return memoryType; }
    public void setMemoryType(String memoryType) { this.memoryType = memoryType; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getEmotionContext() { return emotionContext; }
    public void setEmotionContext(String emotionContext) { this.emotionContext = emotionContext; }

    public Float getImportance() { return importance; }
    public void setImportance(Float importance) { this.importance = importance; }

    public Integer getRecallCount() { return recallCount; }
    public void setRecallCount(Integer recallCount) { this.recallCount = recallCount; }

    public Instant getLastRecalledAt() { return lastRecalledAt; }
    public void setLastRecalledAt(Instant lastRecalledAt) { this.lastRecalledAt = lastRecalledAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
