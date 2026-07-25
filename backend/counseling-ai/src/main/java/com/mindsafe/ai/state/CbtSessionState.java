package com.mindsafe.ai.state;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * CBT 会话状态（持久化到 counseling_sessions.state_path jsonb 字段）
 * <p>
 * 记录 CBT 干预过程中的结构化数据，对齐 design/13 §10.2 记录字段列。
 * 序列化为 JSON 存储在 PostgreSQL jsonb 列中。
 */
public class CbtSessionState {

    /** 当前 CBT 状态 */
    private String currentState;

    /** 状态流转历史（有序） */
    private List<StateTransitionRecord> stateHistory;

    /** 情绪标签（S2 获得） */
    private String emotionLabel;

    /** 情绪强度 1-10（S2 获得） */
    private Integer emotionIntensity;

    /** 场景 ID（S3 路由） */
    private String scenarioId;

    /** 触发事件摘要（S4 确认） */
    private String triggerEventSummary;

    /** 自动想法（S5 捕捉） */
    private String autoThought;

    /** 思维模式（S5 识别） */
    private String thinkingPattern;

    /** 平衡想法/替代想法（S6 重构） */
    private String balancedThought;

    /** 微行动（S7 选择） */
    private String microAction;

    /** 行动执行者（S7） */
    private String actionOwner;

    /** 最终风险等级（S8 复检） */
    private Integer finalRiskLevel;

    /** 干预后情绪（S8 复检） */
    private String emotionAfter;

    /** 升级原因（S9） */
    private String escalationReason;

    /** 最后更新时间 */
    private Instant lastUpdatedAt;

    public CbtSessionState() {
        this.currentState = CbtState.S0_START.name();
        this.stateHistory = new ArrayList<>();
        this.lastUpdatedAt = Instant.now();
    }

    /**
     * 记录状态转移
     */
    public void recordTransition(CbtState from, CbtState to, String trigger) {
        this.currentState = to.name();
        this.stateHistory.add(new StateTransitionRecord(
                from.name(), to.name(), trigger, Instant.now()));
        this.lastUpdatedAt = Instant.now();
    }

    /**
     * 应用状态机转移结果
     */
    public void applyTransition(CbtStateMachine.TransitionResult result, CbtState fromState) {
        if (result.transitioned()) {
            recordTransition(fromState, result.toState(), result.trigger());
        }
    }

    public CbtState getCurrentCbtState() {
        return CbtState.fromString(currentState);
    }

    // ===== Getters & Setters =====

    public String getCurrentState() { return currentState; }
    public void setCurrentState(String currentState) { this.currentState = currentState; }

    public List<StateTransitionRecord> getStateHistory() { return stateHistory; }
    public void setStateHistory(List<StateTransitionRecord> stateHistory) { this.stateHistory = stateHistory; }

    public String getEmotionLabel() { return emotionLabel; }
    public void setEmotionLabel(String emotionLabel) { this.emotionLabel = emotionLabel; }

    public Integer getEmotionIntensity() { return emotionIntensity; }
    public void setEmotionIntensity(Integer emotionIntensity) { this.emotionIntensity = emotionIntensity; }

    public String getScenarioId() { return scenarioId; }
    public void setScenarioId(String scenarioId) { this.scenarioId = scenarioId; }

    public String getTriggerEventSummary() { return triggerEventSummary; }
    public void setTriggerEventSummary(String triggerEventSummary) { this.triggerEventSummary = triggerEventSummary; }

    public String getAutoThought() { return autoThought; }
    public void setAutoThought(String autoThought) { this.autoThought = autoThought; }

    public String getThinkingPattern() { return thinkingPattern; }
    public void setThinkingPattern(String thinkingPattern) { this.thinkingPattern = thinkingPattern; }

    public String getBalancedThought() { return balancedThought; }
    public void setBalancedThought(String balancedThought) { this.balancedThought = balancedThought; }

    public String getMicroAction() { return microAction; }
    public void setMicroAction(String microAction) { this.microAction = microAction; }

    public String getActionOwner() { return actionOwner; }
    public void setActionOwner(String actionOwner) { this.actionOwner = actionOwner; }

    public Integer getFinalRiskLevel() { return finalRiskLevel; }
    public void setFinalRiskLevel(Integer finalRiskLevel) { this.finalRiskLevel = finalRiskLevel; }

    public String getEmotionAfter() { return emotionAfter; }
    public void setEmotionAfter(String emotionAfter) { this.emotionAfter = emotionAfter; }

    public String getEscalationReason() { return escalationReason; }
    public void setEscalationReason(String escalationReason) { this.escalationReason = escalationReason; }

    public Instant getLastUpdatedAt() { return lastUpdatedAt; }
    public void setLastUpdatedAt(Instant lastUpdatedAt) { this.lastUpdatedAt = lastUpdatedAt; }

    /** 状态转移记录 */
    public record StateTransitionRecord(
            String fromState,
            String toState,
            String trigger,
            Instant timestamp
    ) {}
}
