package com.mindsafe.ai.orchestrator;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 情绪编排效果量化（ORCH-008，design/44 P3）
 * <p>
 * 情绪适配 vs 不适配的效果量化，并入 design/39 A/B 实验框架。
 * <ul>
 *   <li>稳定回落速度：情绪从 ACTIVATED → STABLE 所需轮数</li>
 *   <li>会话深度：有效对话轮数（非单字/非沉默）</li>
 *   <li>满意度：三表情反馈（接 AB-002）</li>
 *   <li>适配判定：进入情绪 × 策略匹配度</li>
 * </ul>
 * 纯函数实现。接线时由会话结束异步任务 + 实验分析消费。
 */
@Component
public class EmotionOrchestrationEvaluator {

    /** 有效轮最短内容长度（少于此视为无效轮） */
    public static final int MIN_MEANINGFUL_LENGTH = 4;

    /** 情绪适配的效果提升阈值（适配组比不适配组好 > 此值视为有效） */
    public static final double EFFECTIVENESS_LIFT_THRESHOLD = 0.1;

    // ==================== 稳定回落速度 ====================

    /** 回落结果 */
    public record RecoveryResult(
            int turnsToStable,
            boolean recovered,
            String entryEmotion,
            String finalEmotion
    ) {
    }

    /**
     * 计算情绪稳定回落速度。
     * 从 ACTIVATED 状态开始，到首次进入 STABLE 的轮数。
     *
     * @param emotionStates 每轮的情绪状态序列
     * @return 回落结果
     */
    public RecoveryResult measureRecovery(List<String> emotionStates) {
        if (emotionStates == null || emotionStates.isEmpty()) {
            return new RecoveryResult(0, false, "unknown", "unknown");
        }

        String entry = emotionStates.get(0);
        int turnsToStable = -1;

        for (int i = 1; i < emotionStates.size(); i++) {
            if ("STABLE".equalsIgnoreCase(emotionStates.get(i))) {
                turnsToStable = i;
                break;
            }
        }

        String finalEmotion = emotionStates.get(emotionStates.size() - 1);
        boolean recovered = turnsToStable > 0;

        return new RecoveryResult(
                recovered ? turnsToStable : emotionStates.size(),
                recovered, entry, finalEmotion);
    }

    // ==================== 会话深度 ====================

    /**
     * 计算有效会话深度（排除单字/沉默轮）。
     *
     * @param studentMessages 学生消息列表
     * @return 有效轮数
     */
    public int measureDepth(List<String> studentMessages) {
        if (studentMessages == null) return 0;
        return (int) studentMessages.stream()
                .filter(m -> m != null && m.trim().length() >= MIN_MEANINGFUL_LENGTH)
                .count();
    }

    // ==================== 适配判定 ====================

    /** 适配评估结果 */
    public record FitAssessment(
            boolean adapted,
            String entryEmotion,
            String strategyUsed,
            String reason
    ) {
    }

    /**
     * 判断情绪编排是否适配（进入情绪 × 策略匹配）。
     * 规则：
     * - sad → 共情镜映策略 = 适配
     * - angry → 中立不评判 + 情绪命名 = 适配
     * - anxious → 接地/呼吸引导 = 适配
     * - happy → 积极延续 = 适配
     * - 危机 → 安全话术（不参与适配评估）
     *
     * @param entryEmotion  进入情绪
     * @param strategyUsed  实际使用的策略标识
     * @return 适配评估
     */
    public FitAssessment assessFit(String entryEmotion, String strategyUsed) {
        if (entryEmotion == null || strategyUsed == null) {
            return new FitAssessment(false, entryEmotion, strategyUsed, "信息不足");
        }

        String emotion = entryEmotion.toLowerCase();
        String strategy = strategyUsed.toLowerCase();

        boolean adapted = switch (emotion) {
            case "sad", "scared" -> strategy.contains("empathy") || strategy.contains("mirror");
            case "angry" -> strategy.contains("neutral") || strategy.contains("naming");
            case "anxious", "nervous" -> strategy.contains("grounding") || strategy.contains("breathing");
            case "happy" -> strategy.contains("positive") || strategy.contains("extend");
            default -> true; // 未知情绪不判不适配
        };

        return new FitAssessment(adapted, entryEmotion, strategyUsed,
                adapted ? "策略与情绪匹配" : "策略与情绪不匹配");
    }

    // ==================== 综合效果对比 ====================

    /** 效果对比结果 */
    public record EffectComparison(
            double adaptedRecoveryMean,
            double nonAdaptedRecoveryMean,
            double adaptedDepthMean,
            double nonAdaptedDepthMean,
            double adaptedSatisfactionMean,
            double nonAdaptedSatisfactionMean,
            boolean emotionAdaptationEffective
    ) {
    }

    /**
     * 对比适配组 vs 不适配组的三维效果。
     *
     * @param adaptedRecovery     适配组回落轮数列表
     * @param nonAdaptedRecovery  不适配组回落轮数列表
     * @param adaptedDepth        适配组会话深度列表
     * @param nonAdaptedDepth     不适配组会话深度列表
     * @param adaptedSatisfaction 适配组满意度列表
     * @param nonAdaptedSatisfaction 不适配组满意度列表
     * @return 对比结果
     */
    public EffectComparison compare(List<Double> adaptedRecovery, List<Double> nonAdaptedRecovery,
                                    List<Double> adaptedDepth, List<Double> nonAdaptedDepth,
                                    List<Double> adaptedSatisfaction, List<Double> nonAdaptedSatisfaction) {
        double adaptedRecMean = mean(adaptedRecovery);
        double nonAdaptedRecMean = mean(nonAdaptedRecovery);
        double adaptedDepMean = mean(adaptedDepth);
        double nonAdaptedDepMean = mean(nonAdaptedDepth);
        double adaptedSatMean = mean(adaptedSatisfaction);
        double nonAdaptedSatMean = mean(nonAdaptedSatisfaction);

        // 适配有效：回落更快（少）+ 深度更深 + 满意度更高（至少两项达标）
        int positiveSignals = 0;
        if (adaptedRecMean < nonAdaptedRecMean) positiveSignals++;
        if (nonAdaptedDepMean > 0 && (adaptedDepMean - nonAdaptedDepMean) / nonAdaptedDepMean > EFFECTIVENESS_LIFT_THRESHOLD)
            positiveSignals++;
        if (nonAdaptedSatMean > 0 && (adaptedSatMean - nonAdaptedSatMean) / nonAdaptedSatMean > EFFECTIVENESS_LIFT_THRESHOLD)
            positiveSignals++;

        return new EffectComparison(adaptedRecMean, nonAdaptedRecMean,
                adaptedDepMean, nonAdaptedDepMean, adaptedSatMean, nonAdaptedSatMean,
                positiveSignals >= 2);
    }

    private double mean(List<Double> values) {
        if (values == null || values.isEmpty()) return 0;
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }
}
