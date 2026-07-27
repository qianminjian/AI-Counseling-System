package com.mindsafe.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.mindsafe.domain.typehandler.JsonbTypeHandler;

import java.time.Instant;
import java.util.UUID;

/**
 * 学生心理画像实体（对应 tenant_template.student_profiles）
 * 只存结构化统计指标，不存原始对话
 */
@TableName(value = "student_profiles", schema = "tenant_template", autoResultMap = true)
public class StudentProfile {

    @TableId(value = "profile_id", type = IdType.ASSIGN_UUID)
    private UUID profileId;

    private UUID tenantId;
    private UUID userId;

    /** 情绪基线：分布/波动度/触发主题 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String emotionBaseline;

    /** 沟通偏好：表达深度/偏好风格/活跃时段 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String communicationPref;

    /** 心理韧性：恢复速度/应对技巧/自我效能 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String resilience;

    /** 风险轨迹：等级分布/趋势/敏感主题 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String riskTrajectory;

    /** 社交图谱：关键人物(代号化)/满意度/求助意愿 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String socialGraph;

    /** 成长轨迹：频率/里程碑/干预有效性 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String growthTrack;

    /** 性格特征（LLM 提炼）：introversion/sensitivity/curiosity/dominant_interests */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String personalityTraits;

    private Integer version;
    private Integer totalSessions;
    private Instant lastUpdatedAt;
    private Instant createdAt;

    // ===== Getters & Setters =====

    public UUID getProfileId() { return profileId; }
    public void setProfileId(UUID profileId) { this.profileId = profileId; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public String getEmotionBaseline() { return emotionBaseline; }
    public void setEmotionBaseline(String emotionBaseline) { this.emotionBaseline = emotionBaseline; }

    public String getCommunicationPref() { return communicationPref; }
    public void setCommunicationPref(String communicationPref) { this.communicationPref = communicationPref; }

    public String getResilience() { return resilience; }
    public void setResilience(String resilience) { this.resilience = resilience; }

    public String getRiskTrajectory() { return riskTrajectory; }
    public void setRiskTrajectory(String riskTrajectory) { this.riskTrajectory = riskTrajectory; }

    public String getSocialGraph() { return socialGraph; }
    public void setSocialGraph(String socialGraph) { this.socialGraph = socialGraph; }

    public String getGrowthTrack() { return growthTrack; }
    public void setGrowthTrack(String growthTrack) { this.growthTrack = growthTrack; }

    public String getPersonalityTraits() { return personalityTraits; }
    public void setPersonalityTraits(String personalityTraits) { this.personalityTraits = personalityTraits; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public Integer getTotalSessions() { return totalSessions; }
    public void setTotalSessions(Integer totalSessions) { this.totalSessions = totalSessions; }

    public Instant getLastUpdatedAt() { return lastUpdatedAt; }
    public void setLastUpdatedAt(Instant lastUpdatedAt) { this.lastUpdatedAt = lastUpdatedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
