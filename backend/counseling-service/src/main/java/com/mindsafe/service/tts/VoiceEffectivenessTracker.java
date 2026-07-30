package com.mindsafe.service.tts;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 音色效果回收与进化（TMATCH-003，design/48 P2/P3）
 * <p>
 * <ul>
 *   <li>效果回收：播放完成率 / 手动切换频率 / 会话参与度</li>
 *   <li>会话内稳定性：依恋原则，一次会话默认不切 persona</li>
 *   <li>跨会话缓慢演进：画像变化时渐进调整，不突变</li>
 *   <li>匹配规则 A/B 进化：手动切换为最强负反馈，驱动默认规则修正</li>
 * </ul>
 * 纯函数实现。接线时由前端埋点 + 会话结束异步任务消费。
 */
@Component
public class VoiceEffectivenessTracker {

    /** 频繁切换阈值：单会话内切换 ≥ 此次数视为默认不合适 */
    public static final int FREQUENT_SWITCH_THRESHOLD = 2;

    /** 完成率过低阈值 */
    public static final double LOW_COMPLETION_RATE = 0.5;

    /** 跨会话演进最小间隔（会话数）：至少间隔 N 次才允许换 persona */
    public static final int EVOLUTION_MIN_GAP = 5;

    // ==================== 效果回收 ====================

    /** 音色效果指标 */
    public record VoiceMetrics(
            String voiceId,
            int totalSessions,
            double avgCompletionRate,
            int manualSwitchCount,
            double avgSessionDuration,
            double avgEngagementScore
    ) {
        /** 切换率 */
        public double switchRate() {
            return totalSessions == 0 ? 0 : (double) manualSwitchCount / totalSessions;
        }
    }

    /** 效果评估结论 */
    public record EffectivenessVerdict(
            String voiceId,
            boolean effective,
            String reason,
            boolean suggestRuleChange
    ) {
    }

    /**
     * 评估某音色的效果。
     *
     * @param metrics 音色指标
     * @return 效果评估
     */
    public EffectivenessVerdict evaluate(VoiceMetrics metrics) {
        if (metrics.totalSessions() < 5) {
            return new EffectivenessVerdict(metrics.voiceId(), true,
                    "样本不足（< 5 次），暂不评估", false);
        }

        // 频繁切换 = 最强负反馈
        if (metrics.switchRate() > 0.3) {
            return new EffectivenessVerdict(metrics.voiceId(), false,
                    "切换率 " + String.format("%.0f%%", metrics.switchRate() * 100)
                    + " 过高，默认规则可能不合适", true);
        }

        // 完成率过低
        if (metrics.avgCompletionRate() < LOW_COMPLETION_RATE) {
            return new EffectivenessVerdict(metrics.voiceId(), false,
                    "播放完成率 " + String.format("%.0f%%", metrics.avgCompletionRate() * 100)
                    + " 过低", true);
        }

        return new EffectivenessVerdict(metrics.voiceId(), true,
                "效果正常（完成率 " + String.format("%.0f%%", metrics.avgCompletionRate() * 100)
                + "，切换率 " + String.format("%.0f%%", metrics.switchRate() * 100) + "）", false);
    }

    // ==================== 会话内稳定性 ====================

    /** 稳定性决策 */
    public record StabilityDecision(
            boolean allowSwitch,
            String currentVoiceId,
            String reason
    ) {
    }

    /**
     * 判断会话内是否允许切换 persona（依恋原则）。
     * 规则：仅学生手动切换 或 安全/危机强制稳定基调时允许。
     *
     * @param isManualSwitch   是否学生主动切换
     * @param isSafetyOverride 是否安全/危机场景强制
     * @param switchCountInSession 本次会话已切换次数
     * @param currentVoiceId   当前音色
     * @return 稳定性决策
     */
    public StabilityDecision canSwitchInSession(boolean isManualSwitch, boolean isSafetyOverride,
                                                int switchCountInSession, String currentVoiceId) {
        if (isSafetyOverride) {
            return new StabilityDecision(true, currentVoiceId,
                    "安全/危机场景强制稳定基调（locked）");
        }

        if (!isManualSwitch) {
            return new StabilityDecision(false, currentVoiceId,
                    "会话内非手动不切 persona（依恋原则）");
        }

        if (switchCountInSession >= FREQUENT_SWITCH_THRESHOLD) {
            // 允许但标记为频繁切换（负反馈信号）
            return new StabilityDecision(true, currentVoiceId,
                    "允许手动切换，但本会话已切换 " + switchCountInSession + " 次（频繁）");
        }

        return new StabilityDecision(true, currentVoiceId, "学生手动切换，允许");
    }

    // ==================== 跨会话演进 ====================

    /**
     * 判断跨会话是否允许演进到新 persona。
     * 规则：距上次切换至少 EVOLUTION_MIN_GAP 次会话。
     *
     * @param sessionsSinceLastSwitch 距上次切换的会话数
     * @param profileChanged          画像是否发生变化
     * @return true=允许演进
     */
    public boolean canEvolveAcrossSessions(int sessionsSinceLastSwitch, boolean profileChanged) {
        if (!profileChanged) return false;
        return sessionsSinceLastSwitch >= EVOLUTION_MIN_GAP;
    }

    // ==================== A/B 进化 ====================

    /** 规则进化建议 */
    public record EvolutionSuggestion(
            String segment,
            String currentDefault,
            String suggestedDefault,
            int switchAwayCount,
            int totalInSegment,
            double switchAwayRate
    ) {
    }

    /**
     * 分析某画像分段是否需要修正默认规则。
     * 手动切换是最强负反馈：某类孩子普遍从默认换到某 persona → 修正默认。
     *
     * @param segment          画像分段（如 "female_grade3_introvert"）
     * @param currentDefault   当前默认 persona
     * @param switchAwayCount  从默认切走的次数
     * @param totalInSegment   该分段总会话数
     * @param mostSwitchedTo   最常切换到的 persona
     * @return 进化建议，null=不需要修正
     */
    public EvolutionSuggestion suggestRuleEvolution(String segment, String currentDefault,
                                                    int switchAwayCount, int totalInSegment,
                                                    String mostSwitchedTo) {
        if (totalInSegment < 10) return null; // 样本不足

        double switchAwayRate = (double) switchAwayCount / totalInSegment;
        // 超过 25% 的用户从默认切走 → 建议修正
        if (switchAwayRate > 0.25 && mostSwitchedTo != null && !mostSwitchedTo.equals(currentDefault)) {
            return new EvolutionSuggestion(segment, currentDefault, mostSwitchedTo,
                    switchAwayCount, totalInSegment, switchAwayRate);
        }
        return null;
    }
}
