package com.mindsafe.service.voice;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 趋势异常信号与阈值自适应（VCL-003，design/47 P2/P3）
 * <p>
 * <ul>
 *   <li>趋势异常→教师关注信号 + 量表复测建议（非诊断、非报警）</li>
 *   <li>SER 标注回流：弱标签集 + 准确度/混淆矩阵评估</li>
 *   <li>情绪类别分层阈值自适应（按类别分别设阈值，配置化）</li>
 * </ul>
 * 纯函数实现。接线时由趋势聚合定时任务 + 教师工作台消费。
 */
@Component
public class TrendAnomalySignaler {

    /** 默认置信阈值 */
    public static final double DEFAULT_CONFIDENCE_THRESHOLD = 0.6;

    /** 连续恶化会话数阈值（达到即生成关注信号） */
    public static final int WORSENING_SESSION_THRESHOLD = 3;

    /** 负面占比阈值（超过即视为异常） */
    public static final double NEGATIVE_RATIO_THRESHOLD = 0.7;

    // ==================== 趋势异常→关注信号 ====================

    /** 关注信号 */
    public record AttentionSignal(
            String studentId,
            String signalType,
            String description,
            boolean suggestScaleRetest,
            boolean routeToTeacher,
            int worseningSessions
    ) {
    }

    /**
     * 根据跨会话趋势判断是否生成教师关注信号。
     * 规则：连续恶化 ≥ 3 次 或 负面占比 > 0.7 → 关注信号（非诊断）。
     *
     * @param studentId         学生 ID
     * @param worseningSessions 连续恶化会话数
     * @param negativeRatio     近期负面占比
     * @param avgConfidence     平均置信度（低置信降权）
     * @return 关注信号，null=不需要
     */
    public AttentionSignal evaluate(String studentId, int worseningSessions,
                                    double negativeRatio, double avgConfidence) {
        // 低置信趋势降权：平均置信 < 0.5 时不生成信号
        if (avgConfidence < 0.5) return null;

        boolean worseningTrigger = worseningSessions >= WORSENING_SESSION_THRESHOLD;
        boolean ratioTrigger = negativeRatio > NEGATIVE_RATIO_THRESHOLD;

        if (!worseningTrigger && !ratioTrigger) return null;

        String type;
        String desc;
        boolean suggestRetest;

        if (worseningTrigger && ratioTrigger) {
            type = "WORSENING_HIGH_NEGATIVE";
            desc = "连续 " + worseningSessions + " 次会话情绪恶化，负面占比 "
                    + String.format("%.0f%%", negativeRatio * 100) + "，建议主动关怀";
            suggestRetest = true;
        } else if (worseningTrigger) {
            type = "WORSENING_TREND";
            desc = "连续 " + worseningSessions + " 次会话情绪呈恶化趋势，建议关注";
            suggestRetest = worseningSessions >= 4;
        } else {
            type = "HIGH_NEGATIVE_RATIO";
            desc = "近期负面情绪占比 " + String.format("%.0f%%", negativeRatio * 100) + "，建议关注";
            suggestRetest = true;
        }

        return new AttentionSignal(studentId, type, desc, suggestRetest, true, worseningSessions);
    }

    // ==================== SER 标注回流评估 ====================

    /** 混淆矩阵结果 */
    public record ConfusionResult(
            String predictedEmotion,
            String actualEmotion,
            int count
    ) {
    }

    /** SER 准确度评估 */
    public record SerAccuracyReport(
            int totalSamples,
            int correctCount,
            double accuracy,
            List<ConfusionResult> topConfusions,
            String weakestEmotion
    ) {
    }

    /**
     * 从弱标签集评估 SER 准确度。
     * 弱标签来源：文本×语音一致样本（高可信伪标签）。
     *
     * @param predictions SER 预测标签
     * @param weakLabels  弱标签（文本情绪一致的伪标签）
     * @return 准确度报告
     */
    public SerAccuracyReport evaluateAccuracy(List<String> predictions, List<String> weakLabels) {
        if (predictions == null || weakLabels == null
                || predictions.size() != weakLabels.size() || predictions.isEmpty()) {
            return new SerAccuracyReport(0, 0, 0, List.of(), null);
        }

        int n = predictions.size();
        int correct = 0;
        Map<String, Integer> confusionCounts = new HashMap<>();
        Map<String, int[]> perEmotion = new HashMap<>(); // [correct, total]

        for (int i = 0; i < n; i++) {
            String pred = predictions.get(i);
            String actual = weakLabels.get(i);

            perEmotion.computeIfAbsent(actual, k -> new int[2]);
            perEmotion.get(actual)[1]++;

            if (pred.equals(actual)) {
                correct++;
                perEmotion.get(actual)[0]++;
            } else {
                String key = pred + "→" + actual;
                confusionCounts.merge(key, 1, Integer::sum);
            }
        }

        // Top 混淆对
        List<ConfusionResult> topConfusions = confusionCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(5)
                .map(e -> {
                    String[] parts = e.getKey().split("→");
                    return new ConfusionResult(parts[0], parts[1], e.getValue());
                })
                .toList();

        // 最弱情绪（准确率最低）
        String weakest = perEmotion.entrySet().stream()
                .filter(e -> e.getValue()[1] >= 3) // 至少 3 个样本
                .min((a, b) -> Double.compare(
                        (double) a.getValue()[0] / a.getValue()[1],
                        (double) b.getValue()[0] / b.getValue()[1]))
                .map(Map.Entry::getKey)
                .orElse(null);

        return new SerAccuracyReport(n, correct, (double) correct / n, topConfusions, weakest);
    }

    // ==================== 阈值自适应 ====================

    /** 类别阈值配置 */
    public record ThresholdConfig(
            String emotion,
            double threshold,
            int sampleCount,
            double precision
    ) {
    }

    /**
     * 根据评估结果自适应调整各类别阈值。
     * 规则：精确率 < 0.7 的类别提高阈值（更保守），> 0.9 的可降低（更灵敏）。
     *
     * @param emotion        情绪类别
     * @param currentThreshold 当前阈值
     * @param precision      该类别精确率
     * @param sampleCount    样本量
     * @return 新阈值配置
     */
    public ThresholdConfig adaptThreshold(String emotion, double currentThreshold,
                                          double precision, int sampleCount) {
        if (sampleCount < 10) {
            return new ThresholdConfig(emotion, currentThreshold, sampleCount, precision);
        }

        double newThreshold = currentThreshold;
        if (precision < 0.7) {
            // 精确率低 → 提高阈值（更保守，减少误判）
            newThreshold = Math.min(0.9, currentThreshold + 0.1);
        } else if (precision > 0.9 && currentThreshold > 0.5) {
            // 精确率高 → 可降低阈值（更灵敏）
            newThreshold = Math.max(0.5, currentThreshold - 0.05);
        }

        return new ThresholdConfig(emotion, newThreshold, sampleCount, precision);
    }

    /**
     * 批量自适应：对所有类别计算新阈值。
     *
     * @param configs 当前各类别配置（emotion → [threshold, precision, sampleCount]）
     * @return 调整后配置
     */
    public Map<String, ThresholdConfig> adaptAll(Map<String, double[]> configs) {
        Map<String, ThresholdConfig> result = new HashMap<>();
        for (var entry : configs.entrySet()) {
            double[] v = entry.getValue();
            result.put(entry.getKey(),
                    adaptThreshold(entry.getKey(), v[0], v[1], (int) v[2]));
        }
        return result;
    }
}
