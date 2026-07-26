package com.mindsafe.domain.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.time.Instant;
import java.util.UUID;

/**
 * 辅导会话实体（对应 tenant_template.counseling_sessions）
 */
@TableName(value = "counseling_sessions", schema = "tenant_template")
public class CounselingSession {

    @TableId(value = "session_id", type = IdType.INPUT)
    private UUID sessionId;

    private UUID tenantId;
    private UUID schoolId;
    private UUID studentUserId;
    private String channel;
    private String interactionMode;
    private Instant startedAt;
    private Instant endedAt;
    private String sessionStatus;
    private Integer riskLevelSnapshot;
    private String transcriptPolicy;
    private String consentVersion;

    /** CBT 状态机路径（jsonb，存储 CbtSessionState 序列化 JSON） */
    private String statePath;

    /** 对话轮次数 */
    private Integer turnCount;

    /** 满意度评分（1-5 星） */
    private Integer satisfactionRating;

    /** 满意度评价文字 */
    private String satisfactionComment;

    /** AI 生成的会话结构化摘要 */
    private String sessionSummary;

    private Instant createdAt;
    private Instant updatedAt;

    // ===== 非 DB 字段（便于业务传参） =====
    @TableField(exist = false)
    private String emotionTag;

    public CounselingSession() {
    }

    public static CounselingSession create(UUID tenantId, UUID studentUserId, String emotionTag, String channel) {
        CounselingSession s = new CounselingSession();
        s.sessionId = UUID.randomUUID();
        s.tenantId = tenantId;
        s.studentUserId = studentUserId;
        s.channel = channel != null ? channel : "web";
        s.interactionMode = "text";
        s.startedAt = Instant.now();
        s.sessionStatus = "active";
        s.riskLevelSnapshot = 0;
        s.transcriptPolicy = "summary_only";
        s.emotionTag = emotionTag;
        s.createdAt = Instant.now();
        s.updatedAt = Instant.now();
        return s;
    }

    public void end() {
        this.endedAt = Instant.now();
        this.sessionStatus = "completed";
        this.updatedAt = Instant.now();
    }

    public void upgradeRiskLevel(int level) {
        if (level > (this.riskLevelSnapshot == null ? 0 : this.riskLevelSnapshot)) {
            this.riskLevelSnapshot = level;
            this.updatedAt = Instant.now();
        }
    }

    // ===== Getters & Setters =====

    public UUID getSessionId() { return sessionId; }
    public void setSessionId(UUID sessionId) { this.sessionId = sessionId; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public UUID getSchoolId() { return schoolId; }
    public void setSchoolId(UUID schoolId) { this.schoolId = schoolId; }

    public UUID getStudentUserId() { return studentUserId; }
    public void setStudentUserId(UUID studentUserId) { this.studentUserId = studentUserId; }

    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }

    public String getInteractionMode() { return interactionMode; }
    public void setInteractionMode(String interactionMode) { this.interactionMode = interactionMode; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }

    public String getSessionStatus() { return sessionStatus; }
    public void setSessionStatus(String sessionStatus) { this.sessionStatus = sessionStatus; }

    public Integer getRiskLevelSnapshot() { return riskLevelSnapshot; }
    public void setRiskLevelSnapshot(Integer riskLevelSnapshot) { this.riskLevelSnapshot = riskLevelSnapshot; }

    public String getTranscriptPolicy() { return transcriptPolicy; }
    public void setTranscriptPolicy(String transcriptPolicy) { this.transcriptPolicy = transcriptPolicy; }

    public String getConsentVersion() { return consentVersion; }
    public void setConsentVersion(String consentVersion) { this.consentVersion = consentVersion; }

    public String getStatePath() { return statePath; }
    public void setStatePath(String statePath) { this.statePath = statePath; }

    public Integer getTurnCount() { return turnCount; }
    public void setTurnCount(Integer turnCount) { this.turnCount = turnCount; }

    public Integer getSatisfactionRating() { return satisfactionRating; }
    public void setSatisfactionRating(Integer satisfactionRating) { this.satisfactionRating = satisfactionRating; }

    public String getSatisfactionComment() { return satisfactionComment; }
    public void setSatisfactionComment(String satisfactionComment) { this.satisfactionComment = satisfactionComment; }

    public String getSessionSummary() { return sessionSummary; }
    public void setSessionSummary(String sessionSummary) { this.sessionSummary = sessionSummary; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public String getEmotionTag() { return emotionTag; }
    public void setEmotionTag(String emotionTag) { this.emotionTag = emotionTag; }
}
