package com.mindsafe.ai.state;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 会话级状态对象（Redis 持久化，替代 ConcurrentHashMap 内存方案）
 * <p>
 * 对齐 design/13 §4.4 ConversationState。
 * Redis key: session:{sessionId}:state，TTL = 2h。
 * <p>
 * 包含：会话元数据 + 轮次计数 + CBT 状态 + 情绪趋势 + 语音情绪历史。
 */
public class ConversationState {

    // ===== 会话元数据 =====
    private UUID sessionId;
    private UUID tenantId;
    private UUID studentUserId;
    private String emotionTag;
    private String channel;
    private int gradeLevel;

    // ===== 轮次管理 =====
    private int turnCount;
    private int maxTurns;

    // ===== CBT 状态 =====
    private CbtSessionState cbtState;

    // ===== 情绪趋势追踪 =====
    private List<EmotionRecord> emotionHistory;

    // ===== 时间戳 =====
    private Instant createdAt;
    private Instant lastActiveAt;

    public ConversationState() {
        this.turnCount = 0;
        this.maxTurns = 12;
        this.cbtState = new CbtSessionState();
        this.emotionHistory = new ArrayList<>();
        this.createdAt = Instant.now();
        this.lastActiveAt = Instant.now();
    }

    public static ConversationState create(UUID sessionId, UUID tenantId, UUID studentUserId,
                                           String emotionTag, String channel, int gradeLevel) {
        ConversationState state = new ConversationState();
        state.sessionId = sessionId;
        state.tenantId = tenantId;
        state.studentUserId = studentUserId;
        state.emotionTag = emotionTag;
        state.channel = channel;
        state.gradeLevel = gradeLevel;
        return state;
    }

    /** 递增轮次并更新活跃时间 */
    public int incrementTurn() {
        this.turnCount++;
        this.lastActiveAt = Instant.now();
        return this.turnCount;
    }

    /** 是否已超过轮次上限 */
    public boolean isTurnLimitReached() {
        return turnCount >= maxTurns;
    }

    /** 剩余轮次 */
    public int remainingTurns() {
        return Math.max(0, maxTurns - turnCount);
    }

    /** 添加语音情绪记录 */
    public void addEmotionRecord(String emotion, double confidence) {
        emotionHistory.add(new EmotionRecord(emotion, confidence, Instant.now()));
        if (emotionHistory.size() > 10) {
            emotionHistory.remove(0);
        }
    }

    /** 连续消极情绪计数（从最近一条往前数） */
    public int consecutiveNegativeCount() {
        int count = 0;
        for (int i = emotionHistory.size() - 1; i >= 0; i--) {
            String e = emotionHistory.get(i).emotion();
            if ("sad".equals(e) || "fearful".equals(e) || "angry".equals(e) || "disgusted".equals(e)) {
                count++;
            } else {
                break;
            }
        }
        return count;
    }

    /** 获取当前 CBT 状态枚举 */
    public CbtState getCurrentCbtState() {
        return cbtState.getCurrentCbtState();
    }

    // ===== Getters & Setters =====

    public UUID getSessionId() { return sessionId; }
    public void setSessionId(UUID sessionId) { this.sessionId = sessionId; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public UUID getStudentUserId() { return studentUserId; }
    public void setStudentUserId(UUID studentUserId) { this.studentUserId = studentUserId; }

    public String getEmotionTag() { return emotionTag; }
    public void setEmotionTag(String emotionTag) { this.emotionTag = emotionTag; }

    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }

    public int getGradeLevel() { return gradeLevel; }
    public void setGradeLevel(int gradeLevel) { this.gradeLevel = gradeLevel; }

    public int getTurnCount() { return turnCount; }
    public void setTurnCount(int turnCount) { this.turnCount = turnCount; }

    public int getMaxTurns() { return maxTurns; }
    public void setMaxTurns(int maxTurns) { this.maxTurns = maxTurns; }

    public CbtSessionState getCbtState() { return cbtState; }
    public void setCbtState(CbtSessionState cbtState) { this.cbtState = cbtState; }

    public List<EmotionRecord> getEmotionHistory() { return emotionHistory; }
    public void setEmotionHistory(List<EmotionRecord> emotionHistory) { this.emotionHistory = emotionHistory; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getLastActiveAt() { return lastActiveAt; }
    public void setLastActiveAt(Instant lastActiveAt) { this.lastActiveAt = lastActiveAt; }

    /** 语音情绪记录 */
    public record EmotionRecord(String emotion, double confidence, Instant timestamp) {}
}
