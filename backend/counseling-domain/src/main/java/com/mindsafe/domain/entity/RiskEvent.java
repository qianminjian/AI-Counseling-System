package com.mindsafe.domain.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.time.Instant;
import java.util.UUID;

/**
 * 风险事件实体（对应 tenant_template.risk_events）
 */
@TableName(value = "risk_events", schema = "tenant_template")
public class RiskEvent {

    @TableId(value = "risk_event_id", type = IdType.INPUT)
    private UUID riskEventId;

    private UUID tenantId;
    private UUID schoolId;
    private UUID studentUserId;
    private String sourceType;
    private UUID sourceId;
    private String riskType;
    private Integer riskLevel;
    private String detectedBy;
    private Instant detectedAt;
    private String status;
    private UUID assignedUserId;
    private Instant closedAt;

    // DATA-004：预警追踪闭环
    private String resolutionNote;
    private Instant resolvedAt;
    private Instant followUpAt;
    private String followUpNote;
    private Boolean followUpDone;
    private String outcome;

    private Instant createdAt;
    private Instant updatedAt;

    public RiskEvent() {
    }

    public static RiskEvent fromDetection(UUID tenantId, UUID studentUserId, UUID sessionId,
                                          String riskType, int riskLevel) {
        RiskEvent e = new RiskEvent();
        e.riskEventId = UUID.randomUUID();
        e.tenantId = tenantId;
        e.studentUserId = studentUserId;
        e.sourceType = "session";
        e.sourceId = sessionId;
        e.riskType = riskType;
        e.riskLevel = riskLevel;
        e.detectedBy = "keyword_agent";
        e.detectedAt = Instant.now();
        e.status = "open";
        e.createdAt = Instant.now();
        e.updatedAt = Instant.now();
        return e;
    }

    // ===== Getters & Setters =====

    public UUID getRiskEventId() { return riskEventId; }
    public void setRiskEventId(UUID riskEventId) { this.riskEventId = riskEventId; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public UUID getSchoolId() { return schoolId; }
    public void setSchoolId(UUID schoolId) { this.schoolId = schoolId; }

    public UUID getStudentUserId() { return studentUserId; }
    public void setStudentUserId(UUID studentUserId) { this.studentUserId = studentUserId; }

    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }

    public UUID getSourceId() { return sourceId; }
    public void setSourceId(UUID sourceId) { this.sourceId = sourceId; }

    public String getRiskType() { return riskType; }
    public void setRiskType(String riskType) { this.riskType = riskType; }

    public Integer getRiskLevel() { return riskLevel; }
    public void setRiskLevel(Integer riskLevel) { this.riskLevel = riskLevel; }

    public String getDetectedBy() { return detectedBy; }
    public void setDetectedBy(String detectedBy) { this.detectedBy = detectedBy; }

    public Instant getDetectedAt() { return detectedAt; }
    public void setDetectedAt(Instant detectedAt) { this.detectedAt = detectedAt; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public UUID getAssignedUserId() { return assignedUserId; }
    public void setAssignedUserId(UUID assignedUserId) { this.assignedUserId = assignedUserId; }

    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public String getResolutionNote() { return resolutionNote; }
    public void setResolutionNote(String resolutionNote) { this.resolutionNote = resolutionNote; }

    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }

    public Instant getFollowUpAt() { return followUpAt; }
    public void setFollowUpAt(Instant followUpAt) { this.followUpAt = followUpAt; }

    public String getFollowUpNote() { return followUpNote; }
    public void setFollowUpNote(String followUpNote) { this.followUpNote = followUpNote; }

    public Boolean getFollowUpDone() { return followUpDone; }
    public void setFollowUpDone(Boolean followUpDone) { this.followUpDone = followUpDone; }

    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
}
