package com.mindsafe.domain.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.time.Instant;
import java.util.UUID;

/**
 * 放松练习记录实体（对应 tenant_template.relaxation_sessions）
 */
@TableName(value = "relaxation_sessions", schema = TenantSchema.TENANT_TEMPLATE)
public class RelaxationSession {

    @TableId(value = "relaxation_id", type = IdType.INPUT)
    private UUID relaxationId;

    private UUID tenantId;
    private UUID studentUserId;
    private String exerciseType;
    private Integer durationSeconds;
    private Boolean completed;
    private Instant createdAt;

    public RelaxationSession() {}

    public static RelaxationSession create(UUID tenantId, UUID studentUserId,
                                           String exerciseType, int durationSeconds, boolean completed) {
        RelaxationSession r = new RelaxationSession();
        r.relaxationId = UUID.randomUUID();
        r.tenantId = tenantId;
        r.studentUserId = studentUserId;
        r.exerciseType = exerciseType;
        r.durationSeconds = durationSeconds;
        r.completed = completed;
        r.createdAt = Instant.now();
        return r;
    }

    // ===== Getters & Setters =====
    public UUID getRelaxationId() { return relaxationId; }
    public void setRelaxationId(UUID relaxationId) { this.relaxationId = relaxationId; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public UUID getStudentUserId() { return studentUserId; }
    public void setStudentUserId(UUID studentUserId) { this.studentUserId = studentUserId; }

    public String getExerciseType() { return exerciseType; }
    public void setExerciseType(String exerciseType) { this.exerciseType = exerciseType; }

    public Integer getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(Integer durationSeconds) { this.durationSeconds = durationSeconds; }

    public Boolean getCompleted() { return completed; }
    public void setCompleted(Boolean completed) { this.completed = completed; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
