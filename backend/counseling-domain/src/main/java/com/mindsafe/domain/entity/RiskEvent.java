package com.mindsafe.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.mindsafe.domain.typehandler.JsonbTypeHandler;

import java.time.Instant;
import java.util.UUID;

/**
 * 风险事件实体（对应 tenant_template.risk_events）
 */
@TableName(value = "risk_events", schema = "tenant_template")
public class RiskEvent {

    /** C2（2026-08-05）：状态魔法值收敛——待处理（非实时报警/留痕事件均使用） */
    public static final String STATUS_OPEN = "open";

    /** C2（2026-08-05）：状态魔法值收敛——预警已认领（老师接管处理中） */
    public static final String STATUS_CLAIMED = "claimed";

    /** C2（2026-08-05）：状态魔法值收敛——预警已闭环 */
    public static final String STATUS_CLOSED = "closed";

    /** C2（2026-08-05）：状态魔法值收敛——预警已解决（闭环统计口径） */
    public static final String STATUS_RESOLVED = "resolved";

    @TableId(value = "risk_event_id", type = IdType.INPUT)
    private UUID riskEventId;

    private UUID tenantId;
    private UUID schoolId;
    private UUID studentUserId;
    private String sourceType;
    private UUID sourceId;
    private String riskType;
    private Integer riskLevel;

    // A2（2026-08-05）：RISK-203 结构化评分落库（教师端可排序/复核，不再只打日志）
    private Integer riskScore;

    /** reason_codes JSON 数组文本（如 ["intent_explicit","plan_method"]） */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String reasonCodes;

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

    // P0-4：通知 outbox 补偿（pending/sent/failed/dead）
    private String notifyStatus;
    private Integer notifyAttempts;
    private Instant lastNotifyAttemptAt;

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

    public Integer getRiskScore() { return riskScore; }
    public void setRiskScore(Integer riskScore) { this.riskScore = riskScore; }

    public String getReasonCodes() { return reasonCodes; }
    public void setReasonCodes(String reasonCodes) { this.reasonCodes = reasonCodes; }

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

    public String getNotifyStatus() { return notifyStatus; }
    public void setNotifyStatus(String notifyStatus) { this.notifyStatus = notifyStatus; }

    public Integer getNotifyAttempts() { return notifyAttempts; }
    public void setNotifyAttempts(Integer notifyAttempts) { this.notifyAttempts = notifyAttempts; }

    public Instant getLastNotifyAttemptAt() { return lastNotifyAttemptAt; }
    public void setLastNotifyAttemptAt(Instant lastNotifyAttemptAt) { this.lastNotifyAttemptAt = lastNotifyAttemptAt; }
}
