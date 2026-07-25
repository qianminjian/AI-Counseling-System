package com.mindsafe.ai.safety;

import java.util.UUID;

/**
 * 输出安全违规上报端口（依赖倒置）。
 * <p>
 * counseling-ai 定义本接口（port），counseling-service 提供实现（adapter，
 * 负责写 risk_events 与触发教师通知）。因 counseling-service 依赖 counseling-ai，
 * 反向直接引用会造成循环依赖，故通过本接口解耦。
 */
public interface OutputSafetyReporter {

    /**
     * Layer1 实时拦截上报（严重违规：写 risk_events + 触发教师通知）。
     *
     * @param sessionId      会话 ID
     * @param category       违规类目名（如 self_harm_method）
     * @param matchedKeyword 命中的关键词
     * @param snippet        命中时的上下文片段（审计用，已截断）
     */
    void reportLayer1Block(UUID sessionId, String category, String matchedKeyword, String snippet);

    /**
     * Layer2 异步语义审查违规上报（低危留痕：写 risk_events，不触发教师通知，避免轰炸）。
     *
     * @param sessionId   会话 ID
     * @param decision    SAF-002 决策（rewrite / block / escalate）
     * @param reviewJson  SAF-002 审查原始 JSON 输出（审计留痕）
     */
    void reportLayer2Violation(UUID sessionId, String decision, String reviewJson);
}
