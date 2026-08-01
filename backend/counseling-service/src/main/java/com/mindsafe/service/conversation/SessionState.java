package com.mindsafe.service.conversation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.mindsafe.ai.orchestrator.StrategyProfile;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 会话运行时状态（Redis 持久化，替代原 ConcurrentHashMap 内存缓存）。
 * <p>
 * P0-1 审计修复：原 SessionState 为 ConversationServiceImpl 内部类 + ConcurrentHashMap，
 * 重启即丢失、无法水平扩展。迁移至 Redis 后具备持久化 + 多实例共享能力。
 * <p>
 * 序列化方式：Jackson JSON（由 RedisSessionStateStore 负责）。
 * 线程安全说明：同一会话同一时刻仅一个 SSE 流写入（学生端单连接），
 * nudge 与 message 互斥（前端保证），无需分布式锁。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SessionState {

    // ===== 不可变标识（创建时确定） =====
    private UUID sessionId;
    private UUID tenantId;
    private UUID studentUserId;
    private String emotionTag;
    private String channel;
    private String gender;
    private int grade;
    private Double expressionDepth;

    // ===== 轮次与活跃 =====
    private int turnCount;
    private Instant lastActiveAt = Instant.now();

    // ===== 语音情绪历史（最近 10 条） =====
    private List<EmotionRecord> emotionHistory = new ArrayList<>();

    // ===== 冷场决策模型（nudge）状态，design/28 §三 3.4 =====
    private int nudgeCount;
    private Instant lastNudgeAt;
    private String lastStudentMessageType = NudgeDecisionModel.MSG_NORMAL;
    private Instant lastStudentMessageAt = Instant.now();
    private boolean lastAiAskedThinkingQuestion;
    private int maxRiskSeverity;

    // ===== RISK-201：安全响应模式 =====
    private boolean safetyMode;

    // ===== ORCH-003：情绪状态机 =====
    private StrategyProfile.EmotionState emotionState = StrategyProfile.EmotionState.STABLE;
    private int reliefCount;

    // ===== SAFE-202：高敏模式 =====
    private boolean highSensitivity;

    // ===== CTX-Agent：上下文主 Agent 状态 =====
    private String pseudonym;              // 孩子昵称（创建时从 User 表读取）
    private String sessionSummary;         // 滚动摘要（每 4 轮异步更新）
    private List<TopicHint> topicHints = new ArrayList<>();  // 本次会话主题线索（最多 5 条，含轮次追踪）
    private int lastSummaryTurn;           // 上次生成摘要的轮次

    // ===== 会话级个人信息（对话中结构化收集，会话结束即销毁） =====
    private Map<String, String> personalInfo = new LinkedHashMap<>();  // realName/age/grade/class 等

    /** Jackson 反序列化需要无参构造 */
    public SessionState() {}

    public SessionState(UUID sessionId, UUID tenantId, UUID studentUserId, String emotionTag,
                        String channel, String gender, Double expressionDepth, int grade) {
        this.sessionId = sessionId;
        this.tenantId = tenantId;
        this.studentUserId = studentUserId;
        this.emotionTag = emotionTag;
        this.channel = channel;
        this.gender = gender;
        this.expressionDepth = expressionDepth;
        this.grade = grade;
    }

    // ===== 行为方法（与原内部类保持一致） =====

    public int incrementTurnCount() {
        return ++turnCount;
    }

    public void addEmotionRecord(String emotion, double confidence) {
        emotionHistory.add(new EmotionRecord(emotion, confidence, Instant.now()));
        if (emotionHistory.size() > 10) {
            emotionHistory.remove(0);
        }
    }

    /** VCL-001：本会话语音情绪标签快照 */
    public List<String> emotionLabels() {
        return emotionHistory.stream().map(EmotionRecord::emotion).toList();
    }

    /**
     * 标记本次活跃，返回距上次活动的秒数（上限 300s）。
     */
    public long markActiveAndElapsed() {
        Instant now = Instant.now();
        long elapsed = Duration.between(lastActiveAt, now).getSeconds();
        lastActiveAt = now;
        return Math.max(0, Math.min(elapsed, 300));
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

    // ===== 冷场决策模型方法 =====

    public void recordStudentMessage(String messageType) {
        this.lastStudentMessageType = messageType;
        this.lastStudentMessageAt = Instant.now();
        this.nudgeCount = 0;
    }

    public void recordAiReply(String aiReply) {
        this.lastAiAskedThinkingQuestion = isThinkingQuestion(aiReply);
    }

    public void updateMaxRiskSeverity(int severity) {
        if (severity > this.maxRiskSeverity) {
            this.maxRiskSeverity = severity;
        }
    }

    public void enterSafetyMode() { this.safetyMode = true; }
    public boolean inSafetyMode() { return safetyMode; }

    public long secondsSinceLastStudentMessage() {
        return Duration.between(lastStudentMessageAt, Instant.now()).getSeconds();
    }

    /** 暖场护栏：连续 ≤2 次 且 距上次暖场 ≥20s */
    public boolean canNudge() {
        if (nudgeCount >= 2) return false;
        Instant last = lastNudgeAt;
        return last == null || Duration.between(last, Instant.now()).getSeconds() >= 20;
    }

    public void markNudged() {
        nudgeCount++;
        lastNudgeAt = Instant.now();
    }

    // ===== 思考型问题判断（复用原逻辑） =====

    private static final String[] THINKING_CUES = {"你觉得", "你认为", "你怎么看", "你想想",
            "如果", "假如", "为什么", "是什么让你", "你希望", "你想要"};

    private static boolean isThinkingQuestion(String aiReply) {
        if (aiReply == null || aiReply.isEmpty()) return false;
        boolean hasQuestion = aiReply.contains("？") || aiReply.contains("?");
        if (!hasQuestion) return false;
        for (String cue : THINKING_CUES) {
            if (aiReply.contains(cue)) return true;
        }
        return false;
    }

    // ===== Getters / Setters（Jackson 序列化需要） =====

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
    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }
    public int getGrade() { return grade; }
    public void setGrade(int grade) { this.grade = grade; }
    public Double getExpressionDepth() { return expressionDepth; }
    public void setExpressionDepth(Double expressionDepth) { this.expressionDepth = expressionDepth; }
    public int getTurnCount() { return turnCount; }
    public void setTurnCount(int turnCount) { this.turnCount = turnCount; }
    public Instant getLastActiveAt() { return lastActiveAt; }
    public void setLastActiveAt(Instant lastActiveAt) { this.lastActiveAt = lastActiveAt; }
    public List<EmotionRecord> getEmotionHistory() { return emotionHistory; }
    public void setEmotionHistory(List<EmotionRecord> emotionHistory) { this.emotionHistory = emotionHistory; }
    public int getNudgeCount() { return nudgeCount; }
    public void setNudgeCount(int nudgeCount) { this.nudgeCount = nudgeCount; }
    public Instant getLastNudgeAt() { return lastNudgeAt; }
    public void setLastNudgeAt(Instant lastNudgeAt) { this.lastNudgeAt = lastNudgeAt; }
    public String getLastStudentMessageType() { return lastStudentMessageType; }
    public void setLastStudentMessageType(String t) { this.lastStudentMessageType = t; }
    public Instant getLastStudentMessageAt() { return lastStudentMessageAt; }
    public void setLastStudentMessageAt(Instant t) { this.lastStudentMessageAt = t; }
    public boolean isLastAiAskedThinkingQuestion() { return lastAiAskedThinkingQuestion; }
    public void setLastAiAskedThinkingQuestion(boolean v) { this.lastAiAskedThinkingQuestion = v; }
    public int getMaxRiskSeverity() { return maxRiskSeverity; }
    public void setMaxRiskSeverity(int maxRiskSeverity) { this.maxRiskSeverity = maxRiskSeverity; }
    public boolean isSafetyMode() { return safetyMode; }
    public void setSafetyMode(boolean safetyMode) { this.safetyMode = safetyMode; }
    public StrategyProfile.EmotionState getEmotionState() { return emotionState; }
    public void setEmotionState(StrategyProfile.EmotionState emotionState) { this.emotionState = emotionState; }
    public int getReliefCount() { return reliefCount; }
    public void setReliefCount(int reliefCount) { this.reliefCount = reliefCount; }
    public boolean isHighSensitivity() { return highSensitivity; }
    public void setHighSensitivity(boolean highSensitivity) { this.highSensitivity = highSensitivity; }
    public String getPseudonym() { return pseudonym; }
    public void setPseudonym(String pseudonym) { this.pseudonym = pseudonym; }
    public String getSessionSummary() { return sessionSummary; }
    public void setSessionSummary(String sessionSummary) { this.sessionSummary = sessionSummary; }
    public List<TopicHint> getTopicHints() { return topicHints; }
    public void setTopicHints(List<TopicHint> topicHints) { this.topicHints = topicHints != null ? topicHints : new ArrayList<>(); }
    public int getLastSummaryTurn() { return lastSummaryTurn; }
    public void setLastSummaryTurn(int lastSummaryTurn) { this.lastSummaryTurn = lastSummaryTurn; }
    public Map<String, String> getPersonalInfo() { return personalInfo; }
    public void setPersonalInfo(Map<String, String> personalInfo) { this.personalInfo = personalInfo != null ? personalInfo : new LinkedHashMap<>(); }

    /** 更新个人信息（仅当新值非空时覆盖） */
    public void updatePersonalInfo(String key, String value) {
        if (key != null && value != null && !value.isBlank()) {
            personalInfo.put(key, value.trim());
        }
    }

    /** 添加主题线索（最多保留 5 条，去重，记录轮次和出现次数） */
    public void addTopicHint(String topic, int turn) {
        if (topic == null || topic.isBlank()) return;
        for (int i = 0; i < topicHints.size(); i++) {
            if (topicHints.get(i).topic().equals(topic)) {
                topicHints.set(i, new TopicHint(topic, topicHints.get(i).firstTurn(), topicHints.get(i).count() + 1));
                return;
            }
        }
        topicHints.add(new TopicHint(topic, turn, 1));
        if (topicHints.size() > 5) topicHints.remove(0);
    }

    /** 主题线索记录（含轮次追踪） */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TopicHint(String topic, int firstTurn, int count) {}

    /** 语音情绪记录 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EmotionRecord(String emotion, double confidence, Instant timestamp) {}
}
