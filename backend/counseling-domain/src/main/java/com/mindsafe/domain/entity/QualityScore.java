package com.mindsafe.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 对话质量评估实体（AI-001 + AI-002）
 */
@TableName(value = "quality_scores", schema = TenantSchema.TENANT_TEMPLATE)
public class QualityScore {

    @TableId(value = "score_id", type = IdType.ASSIGN_UUID)
    private UUID scoreId;

    private UUID tenantId;
    private UUID sessionId;

    private BigDecimal empathyScore;
    private BigDecimal cbtCompletion;
    private BigDecimal safetyCompliance;
    private BigDecimal engagementScore;
    private BigDecimal overallScore;

    private String evaluator;
    private Boolean flagged;
    private String flagReason;
    private String rawResponse;
    private Instant evaluatedAt;

    // ===== Getters & Setters =====

    public UUID getScoreId() { return scoreId; }
    public void setScoreId(UUID scoreId) { this.scoreId = scoreId; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public UUID getSessionId() { return sessionId; }
    public void setSessionId(UUID sessionId) { this.sessionId = sessionId; }

    public BigDecimal getEmpathyScore() { return empathyScore; }
    public void setEmpathyScore(BigDecimal empathyScore) { this.empathyScore = empathyScore; }

    public BigDecimal getCbtCompletion() { return cbtCompletion; }
    public void setCbtCompletion(BigDecimal cbtCompletion) { this.cbtCompletion = cbtCompletion; }

    public BigDecimal getSafetyCompliance() { return safetyCompliance; }
    public void setSafetyCompliance(BigDecimal safetyCompliance) { this.safetyCompliance = safetyCompliance; }

    public BigDecimal getEngagementScore() { return engagementScore; }
    public void setEngagementScore(BigDecimal engagementScore) { this.engagementScore = engagementScore; }

    public BigDecimal getOverallScore() { return overallScore; }
    public void setOverallScore(BigDecimal overallScore) { this.overallScore = overallScore; }

    public String getEvaluator() { return evaluator; }
    public void setEvaluator(String evaluator) { this.evaluator = evaluator; }

    public Boolean getFlagged() { return flagged; }
    public void setFlagged(Boolean flagged) { this.flagged = flagged; }

    public String getFlagReason() { return flagReason; }
    public void setFlagReason(String flagReason) { this.flagReason = flagReason; }

    public String getRawResponse() { return rawResponse; }
    public void setRawResponse(String rawResponse) { this.rawResponse = rawResponse; }

    public Instant getEvaluatedAt() { return evaluatedAt; }
    public void setEvaluatedAt(Instant evaluatedAt) { this.evaluatedAt = evaluatedAt; }
}
