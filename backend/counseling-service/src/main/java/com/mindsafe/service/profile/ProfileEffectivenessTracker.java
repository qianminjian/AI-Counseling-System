package com.mindsafe.service.profile;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 画像效果回收与自校准（PROF-024，design/46 P2）
 * <p>
 * <ul>
 *   <li>效果回收：有画像 vs 无画像/低置信会话质量对比（对接 design/39 A/B + design/45 四维）</li>
 *   <li>自校准：无效维度降权 + 量表冲突校准</li>
 *   <li>教师侧：画像脱敏摘要 + 订正回流</li>
 * </ul>
 * 纯函数实现。接线时由会话结束异步任务 + 教师端表单消费。
 */
@Component
public class ProfileEffectivenessTracker {

    /** 降权步长（每累积一次无正向效果，权重乘以此因子） */
    public static final double DECAY_FACTOR = 0.85;

    /** 最低权重下限（不会降到 0） */
    public static final double MIN_WEIGHT = 0.1;

    /** 量表冲突阈值（画像值与量表值差 > 此值视为冲突） */
    public static final double SCALE_CONFLICT_THRESHOLD = 0.35;

    /** 效果回收所需最小样本量 */
    public static final int MIN_SAMPLE_FOR_COMPARISON = 10;

    // ==================== 效果回收 ====================

    /** 会话质量对比结果 */
    public record EffectivenessComparison(
            int withProfileCount,
            int withoutProfileCount,
            double withProfileMean,
            double withoutProfileMean,
            double lift,
            boolean significant,
            boolean sufficientSample
    ) {
    }

    /**
     * 对比有画像 vs 无画像的会话质量。
     *
     * @param withProfileScores    有画像会话的四维综合分列表
     * @param withoutProfileScores 无画像/低置信会话的四维综合分列表
     * @return 对比结果
     */
    public EffectivenessComparison compare(List<Double> withProfileScores,
                                           List<Double> withoutProfileScores) {
        int nWith = withProfileScores == null ? 0 : withProfileScores.size();
        int nWithout = withoutProfileScores == null ? 0 : withoutProfileScores.size();
        boolean sufficient = nWith >= MIN_SAMPLE_FOR_COMPARISON && nWithout >= MIN_SAMPLE_FOR_COMPARISON;

        double meanWith = nWith == 0 ? 0 : withProfileScores.stream()
                .mapToDouble(Double::doubleValue).average().orElse(0);
        double meanWithout = nWithout == 0 ? 0 : withoutProfileScores.stream()
                .mapToDouble(Double::doubleValue).average().orElse(0);

        double lift = meanWithout == 0 ? 0 : (meanWith - meanWithout) / meanWithout;
        // 简化显著性：样本充足 + lift > 5% 视为显著
        boolean significant = sufficient && Math.abs(lift) > 0.05;

        return new EffectivenessComparison(nWith, nWithout, meanWith, meanWithout,
                lift, significant, sufficient);
    }

    // ==================== 维度自校准 ====================

    /** 维度校准结果 */
    public record CalibrationResult(
            String dimension,
            double oldWeight,
            double newWeight,
            String action,
            String reason
    ) {
    }

    /**
     * 无效维度降权：某维度被使用但无正向效果累积 → 降低决策权重。
     *
     * @param dimension         维度名
     * @param currentWeight     当前权重
     * @param usedCount         被使用次数
     * @param positiveEffectCount 产生正向效果次数
     * @return 校准结果
     */
    public CalibrationResult calibrateDimension(String dimension, double currentWeight,
                                                int usedCount, int positiveEffectCount) {
        if (usedCount < 5) {
            return new CalibrationResult(dimension, currentWeight, currentWeight,
                    "HOLD", "样本不足（< 5 次使用），暂不调整");
        }

        double effectRate = (double) positiveEffectCount / usedCount;

        // 正向效果率 < 30% → 降权
        if (effectRate < 0.3) {
            double newWeight = Math.max(MIN_WEIGHT, currentWeight * DECAY_FACTOR);
            return new CalibrationResult(dimension, currentWeight, newWeight,
                    "DECAY", "正向效果率 " + String.format("%.0f%%", effectRate * 100)
                    + " < 30%，降权至 " + String.format("%.3f", newWeight));
        }

        // 正向效果率 > 70% → 可适当增权（上限 1.0）
        if (effectRate > 0.7 && currentWeight < 1.0) {
            double newWeight = Math.min(1.0, currentWeight / DECAY_FACTOR);
            return new CalibrationResult(dimension, currentWeight, newWeight,
                    "BOOST", "正向效果率 " + String.format("%.0f%%", effectRate * 100)
                    + " > 70%，增权至 " + String.format("%.3f", newWeight));
        }

        return new CalibrationResult(dimension, currentWeight, currentWeight,
                "HOLD", "效果率正常（30%-70%），维持");
    }

    /**
     * 量表冲突校准：量表结果与画像风险轨迹冲突 → 以量表为准修正。
     *
     * @param profileRiskValue 画像中的风险值
     * @param scaleRiskValue   量表得出的风险值
     * @param profileConfidence 画像置信度
     * @return 校准结果（mergedValue 为修正后的值）
     */
    public CalibrationResult calibrateWithScale(double profileRiskValue, double scaleRiskValue,
                                                double profileConfidence) {
        double diff = Math.abs(profileRiskValue - scaleRiskValue);

        if (diff <= SCALE_CONFLICT_THRESHOLD) {
            return new CalibrationResult("riskTrajectory", profileRiskValue, profileRiskValue,
                    "NO_CONFLICT", "画像与量表一致（差 " + String.format("%.2f", diff) + "）");
        }

        // 冲突：量表为高置信客观信号，以量表为锚修正
        // 修正公式：向量表方向移动，移动量 = 差值 × (1 - profileConfidence × 0.3)
        double correction = (scaleRiskValue - profileRiskValue) * (1 - profileConfidence * 0.3);
        double newValue = profileRiskValue + correction;
        newValue = Math.max(0, Math.min(1, newValue));

        return new CalibrationResult("riskTrajectory", profileRiskValue, newValue,
                "SCALE_CALIBRATE", "量表冲突（差 " + String.format("%.2f", diff)
                + " > " + SCALE_CONFLICT_THRESHOLD + "），以量表锚定修正");
    }

    // ==================== 教师脱敏摘要 ====================

    /** 脱敏级别 */
    public enum SensitivityLevel {
        LOW,      // 可直接展示
        MEDIUM,   // 泛化后展示
        HIGH      // 不展示，仅聚合
    }

    /** 摘要条目 */
    public record SummaryItem(
            String dimension,
            String displayText,
            SensitivityLevel sensitivity,
            boolean visibleToTeacher
    ) {
    }

    /**
     * 生成教师可见的画像脱敏摘要。
     * 规则：LOW 直接展示，MEDIUM 泛化，HIGH 不展示。
     *
     * @param dimension  维度名
     * @param rawValue   原始描述
     * @param sensitivity 敏感级别
     * @return 摘要条目
     */
    public SummaryItem buildTeacherSummary(String dimension, String rawValue,
                                           SensitivityLevel sensitivity) {
        return switch (sensitivity) {
            case LOW -> new SummaryItem(dimension, rawValue, sensitivity, true);
            case MEDIUM -> new SummaryItem(dimension, generalize(rawValue), sensitivity, true);
            case HIGH -> new SummaryItem(dimension, "[已脱敏]", sensitivity, false);
        };
    }

    /** 泛化处理：去除具体人名/事件细节，保留趋势描述 */
    private String generalize(String raw) {
        if (raw == null || raw.length() <= 10) return "近期有波动";
        // 简化：截取前 10 字 + "…"（实际接线时用 LLM 泛化）
        return raw.substring(0, Math.min(raw.length(), 10)) + "…（已泛化）";
    }

    /**
     * 教师订正回流：生成高置信证据记录。
     *
     * @param teacherId  教师 ID
     * @param dimension  订正维度
     * @param correction 订正内容
     * @return provenance=teacher_input 的置信度（固定 0.9）
     */
    public double teacherCorrectionConfidence(String teacherId, String dimension, String correction) {
        // 教师输入为高置信人工证据
        return 0.9;
    }
}
