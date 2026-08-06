package com.mindsafe.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.mindsafe.domain.typehandler.JsonbTypeHandler;
import com.mindsafe.domain.util.MessageSummarySummarizer;

import java.time.Instant;
import java.util.UUID;

/**
 * 消息摘要实体（对应 tenant_template.message_summaries）
 * <p>
 * 设计原则（D-7 路径 C，2026-07-28 两级摘要）：
 * <ul>
 *   <li>常规消息（riskLevel &lt; 2，GREEN/YELLOW）：文本内容为语义提炼物（≤200 字，MessageSummarySummarizer 规则抽取），非原文切片；</li>
 *   <li>风险消息（riskLevel ≥ 2，ORANGE/RED）：原文保真（安全证据 &gt; 数据最小化，截断至 1024 字符）；</li>
 *   <li>原始消息不落库，文本 AES-256-GCM 加密后存储；结构化字段（情绪标签/风险信号/CBT 关键节点）随行保存。</li>
 * </ul>
 * 对齐 design/08 §5.1 "message_summaries 只存结构化摘要" 与 design/09 §3.3 L 分级接线表（L3→ORANGE）。
 */
@TableName(value = "message_summaries", schema = "tenant_template", autoResultMap = true)
public class MessageSummary {

    @TableId(value = "summary_id", type = IdType.INPUT)
    private UUID summaryId;

    private UUID tenantId;
    private UUID sessionId;
    private UUID studentUserId;
    private Integer turnCount;

    /** 情绪标签 JSON 数组，如 ["sad","anxious"] */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String emotionTags;

    /** 话题标签 JSON 数组，如 ["peer_conflict","academic_stress"] */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String topicTags;

    /** 风险信号 JSON 数组 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String riskSignals;

    /** 建议下一步行动 */
    private String suggestedNextAction;

    // ===== V7 扩展字段（per-message 粒度） =====

    /** 消息发送者类型：student / ai */
    private String senderType;

    /** 单条消息情绪标签 */
    private String emotionLabel;

    /** 单条消息风险等级（0=无风险） */
    private Integer riskLevel;

    /**
     * 消息内容摘要（D-7 两级策略：riskLevel &lt; 2 语义提炼 ≤200 字；riskLevel ≥ 2 原文保真截断至 1024 字符。
     * AES-256-GCM 加密后落库，V32 起列类型 TEXT 容纳密文膨胀，AUDIT-P0-3）
     */
    private String contentSummary;

    /** 原文保真风险阈值：riskLevel ≥ 2（ORANGE/RED）不提炼（design/09 §3.3 接线表） */
    private static final int FULL_FIDELITY_RISK_LEVEL = 2;

    /** CBT 结构化字段 JSON */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String cbtFields;

    private Instant createdAt;

    public MessageSummary() {
    }

    /**
     * 创建学生消息摘要（D-7 两级：risk ≥ 2 原文保真截断 1024；risk &lt; 2 语义提炼 ≤200 字）
     */
    public static MessageSummary studentMessage(UUID tenantId, UUID sessionId, UUID studentUserId,
                                                 int turnCount, String contentSummary,
                                                 String emotionLabel, int riskLevel) {
        MessageSummary m = new MessageSummary();
        m.summaryId = UUID.randomUUID();
        m.tenantId = tenantId;
        m.sessionId = sessionId;
        m.studentUserId = studentUserId;
        m.turnCount = turnCount;
        m.senderType = "student";
        m.contentSummary = (riskLevel >= FULL_FIDELITY_RISK_LEVEL)
                ? truncate(contentSummary, 1024)
                : truncate(MessageSummarySummarizer.summarize(contentSummary), 1024);
        m.emotionLabel = emotionLabel;
        m.riskLevel = riskLevel;
        m.emotionTags = emotionLabel != null ? "[\"" + emotionLabel + "\"]" : "[]";
        m.riskSignals = riskLevel > 0 ? "[{\"level\":" + riskLevel + "}]" : "[]";
        m.topicTags = "[]";
        m.createdAt = Instant.now();
        return m;
    }

    /**
     * 创建 AI 回复摘要
     */
    public static MessageSummary aiMessage(UUID tenantId, UUID sessionId, UUID studentUserId,
                                            int turnCount, String contentSummary) {
        MessageSummary m = new MessageSummary();
        m.summaryId = UUID.randomUUID();
        m.tenantId = tenantId;
        m.sessionId = sessionId;
        m.studentUserId = studentUserId;
        m.turnCount = turnCount;
        m.senderType = "ai";
        m.contentSummary = truncate(contentSummary, 1024);
        m.riskLevel = 0;
        m.emotionTags = "[]";
        m.riskSignals = "[]";
        m.topicTags = "[]";
        m.createdAt = Instant.now();
        return m;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }

    // ===== Getters & Setters =====

    public UUID getSummaryId() { return summaryId; }
    public void setSummaryId(UUID summaryId) { this.summaryId = summaryId; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public UUID getSessionId() { return sessionId; }
    public void setSessionId(UUID sessionId) { this.sessionId = sessionId; }

    public UUID getStudentUserId() { return studentUserId; }
    public void setStudentUserId(UUID studentUserId) { this.studentUserId = studentUserId; }

    public Integer getTurnCount() { return turnCount; }
    public void setTurnCount(Integer turnCount) { this.turnCount = turnCount; }

    public String getEmotionTags() { return emotionTags; }
    public void setEmotionTags(String emotionTags) { this.emotionTags = emotionTags; }

    public String getTopicTags() { return topicTags; }
    public void setTopicTags(String topicTags) { this.topicTags = topicTags; }

    public String getRiskSignals() { return riskSignals; }
    public void setRiskSignals(String riskSignals) { this.riskSignals = riskSignals; }

    public String getSuggestedNextAction() { return suggestedNextAction; }
    public void setSuggestedNextAction(String suggestedNextAction) { this.suggestedNextAction = suggestedNextAction; }

    public String getSenderType() { return senderType; }
    public void setSenderType(String senderType) { this.senderType = senderType; }

    public String getEmotionLabel() { return emotionLabel; }
    public void setEmotionLabel(String emotionLabel) { this.emotionLabel = emotionLabel; }

    public Integer getRiskLevel() { return riskLevel; }
    public void setRiskLevel(Integer riskLevel) { this.riskLevel = riskLevel; }

    public String getContentSummary() { return contentSummary; }
    public void setContentSummary(String contentSummary) { this.contentSummary = contentSummary; }

    public String getCbtFields() { return cbtFields; }
    public void setCbtFields(String cbtFields) { this.cbtFields = cbtFields; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
