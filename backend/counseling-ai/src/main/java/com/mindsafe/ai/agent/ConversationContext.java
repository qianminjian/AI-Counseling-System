package com.mindsafe.ai.agent;

import java.util.List;
import java.util.UUID;

/**
 * 对话上下文（请求级状态对象，在 Agent 间流转）
 * <p>
 * 对齐 design/13 §4.4 ConversationState，精简为当前 Phase 所需字段。
 * 后续 Phase 1.6 将扩展为 Redis 持久化版本。
 */
public record ConversationContext(
        UUID sessionId,
        UUID tenantId,
        UUID studentUserId,
        String emotionTag,
        int gradeLevel,
        int turnCount,
        int maxTurns,
        String currentCbtState,
        List<String> stateHistory
) {
    public ConversationContext {
        if (maxTurns <= 0) maxTurns = 12;
        if (stateHistory == null) stateHistory = List.of();
    }

    /** 是否已超过轮次上限 */
    public boolean isTurnLimitReached() {
        return turnCount >= maxTurns;
    }

    /** 剩余轮次 */
    public int remainingTurns() {
        return Math.max(0, maxTurns - turnCount);
    }
}
