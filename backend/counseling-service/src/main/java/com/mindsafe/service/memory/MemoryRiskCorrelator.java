package com.mindsafe.service.memory;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 记忆风险纵向关联与遗忘策略（MEM-103，design/50 P2/P3）
 * <p>
 * <ul>
 *   <li>风险关联：recurring 负面主题→关注信号（非实时报警，危机仍走 04）</li>
 *   <li>遗忘策略升级：时效衰减 / 敏感度分级 / 学生意愿（被遗忘权）/ 数量上限</li>
 *   <li>双向互哺权重：画像影响召回权重，记忆影响画像置信</li>
 * </ul>
 * 纯函数实现。接线时由主题演化定时任务 + 记忆清理定时任务消费。
 */
@Component
public class MemoryRiskCorrelator {

    /** 负面主题出现次数阈值（达到即生成关注信号） */
    public static final int NEGATIVE_THEME_THRESHOLD = 3;

    /** 时间窗口（天）：在此窗口内计数 */
    public static final int CORRELATION_WINDOW_DAYS = 30;

    /** 遗忘：时效衰减天数（超过此天数 + 低重要性 → 候选遗忘） */
    public static final int FORGET_STALE_DAYS = 90;

    /** 遗忘：低重要性阈值 */
    public static final double FORGET_LOW_IMPORTANCE = 0.3;

    /** 遗忘：高敏感内容最大保留天数 */
    public static final int SENSITIVE_MAX_RETENTION_DAYS = 30;

    // ==================== 风险纵向关联 ====================

    /** 风险关注信号 */
    public record RiskSignal(
            String studentId,
            String theme,
            int occurrenceCount,
            int windowDays,
            String signalLevel,
            boolean suggestAttention,
            boolean suggestScaleRetest
    ) {
    }

    /** 主题出现记录 */
    public record ThemeOccurrence(
            String theme,
            boolean negative,
            Instant occurredAt
    ) {
    }

    /**
     * 分析负面主题的纵向趋势，生成关注信号（非实时报警）。
     * 危机级仍走 04 风险管线，此处仅做纵向趋势信号。
     *
     * @param studentId   学生 ID
     * @param occurrences 主题出现记录（已按时间排序）
     * @param now         当前时间
     * @return 关注信号，null=无需关注
     */
    public RiskSignal correlateRisk(String studentId, List<ThemeOccurrence> occurrences, Instant now) {
        if (occurrences == null || occurrences.isEmpty()) return null;

        Instant windowStart = now.minus(CORRELATION_WINDOW_DAYS, ChronoUnit.DAYS);

        // 统计窗口内负面主题出现次数
        String dominantTheme = null;
        int maxCount = 0;
        var themeCounts = new java.util.HashMap<String, Integer>();

        for (ThemeOccurrence occ : occurrences) {
            if (!occ.negative()) continue;
            if (occ.occurredAt().isBefore(windowStart)) continue;
            int count = themeCounts.merge(occ.theme(), 1, Integer::sum);
            if (count > maxCount) {
                maxCount = count;
                dominantTheme = occ.theme();
            }
        }

        if (maxCount < NEGATIVE_THEME_THRESHOLD || dominantTheme == null) return null;

        String level;
        boolean suggestRetest;
        if (maxCount >= 5) {
            level = "ELEVATED";
            suggestRetest = true;
        } else {
            level = "WATCH";
            suggestRetest = false;
        }

        return new RiskSignal(studentId, dominantTheme, maxCount, CORRELATION_WINDOW_DAYS,
                level, true, suggestRetest);
    }

    // ==================== 遗忘策略 ====================

    /** 遗忘决策 */
    public record ForgetDecision(
            boolean shouldForget,
            String reason,
            String action
    ) {
    }

    /** 记忆条目（简化） */
    public record MemoryEntry(
            String memoryId,
            double importance,
            boolean sensitive,
            boolean studentRequestedForget,
            Instant lastRecalledAt,
            Instant createdAt,
            boolean isRecurringTheme
    ) {
    }

    /**
     * 多维遗忘策略判断。
     * 优先级：学生意愿 > 敏感度 > 时效衰减 > 数量淘汰。
     *
     * @param entry 记忆条目
     * @param now   当前时间
     * @return 遗忘决策
     */
    public ForgetDecision evaluateForget(MemoryEntry entry, Instant now) {
        // 1. 学生意愿（被遗忘权，PIPL 精神）——最高优先
        if (entry.studentRequestedForget()) {
            return new ForgetDecision(true, "学生主动要求遗忘（被遗忘权）", "DELETE");
        }

        // 2. recurring_theme 不轻易遗忘（高价值）
        if (entry.isRecurringTheme()) {
            return new ForgetDecision(false, "反复主题，保留", "KEEP");
        }

        // 3. 高敏感内容：不长期留存原文，超期泛化
        if (entry.sensitive()) {
            long daysSinceCreation = ChronoUnit.DAYS.between(entry.createdAt(), now);
            if (daysSinceCreation > SENSITIVE_MAX_RETENTION_DAYS) {
                return new ForgetDecision(true,
                        "高敏感内容超 " + SENSITIVE_MAX_RETENTION_DAYS + " 天，泛化/删除",
                        "GENERALIZE_THEN_DELETE");
            }
            return new ForgetDecision(false, "高敏感但在保留期内", "KEEP");
        }

        // 4. 时效衰减：久未召回 + 低重要性
        long daysSinceRecall = ChronoUnit.DAYS.between(entry.lastRecalledAt(), now);
        if (daysSinceRecall > FORGET_STALE_DAYS && entry.importance() < FORGET_LOW_IMPORTANCE) {
            return new ForgetDecision(true,
                    "久未召回（" + daysSinceRecall + " 天）且低重要性（"
                    + String.format("%.2f", entry.importance()) + "）",
                    "ARCHIVE");
        }

        return new ForgetDecision(false, "保留（活跃/高重要性）", "KEEP");
    }

    // ==================== 双向互哺权重 ====================

    /**
     * 计算记忆→画像回注的权重。
     * 规则：recurring 主题权重高，单次事件权重低；时效衰减。
     *
     * @param isRecurring     是否反复主题
     * @param occurrenceCount 出现次数
     * @param daysSinceLast   距上次出现天数
     * @return 回注权重（0-1）
     */
    public double memoryToProfileWeight(boolean isRecurring, int occurrenceCount, int daysSinceLast) {
        double base = isRecurring ? 0.7 : 0.3;
        double countBoost = Math.min(0.2, occurrenceCount * 0.05);
        double decay = Math.exp(-0.01 * daysSinceLast); // 缓慢衰减
        return Math.min(1.0, (base + countBoost) * decay);
    }

    /**
     * 计算画像→记忆召回的权重调节。
     * 规则：画像中活跃维度（高置信）的记忆更容易被召回。
     *
     * @param profileConfidence 画像维度置信度
     * @param memoryRelevance   记忆与当前语境相关性（0-1）
     * @return 召回权重调节因子
     */
    public double profileToMemoryRecallBoost(double profileConfidence, double memoryRelevance) {
        // 高置信画像维度 × 高相关性 → 更大召回权重
        return 1.0 + profileConfidence * memoryRelevance * 0.5;
    }
}
