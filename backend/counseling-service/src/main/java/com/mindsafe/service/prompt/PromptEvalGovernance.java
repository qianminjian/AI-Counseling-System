package com.mindsafe.service.prompt;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 提示词评估治理（PEVAL-004，design/45 P2）
 * <p>
 * 灰度分阶段放量（5→20→50→100）+ 护栏破线自动回滚 + LLM-as-Judge κ 校准。
 * <ul>
 *   <li>放量阶段：每阶段观察护栏指标，达标才进下一阶段</li>
 *   <li>自动回滚：safety_compliance 均值破线 / 护栏拦截率突增 → 回滚 last-known-good</li>
 *   <li>κ 校准：judge 与人工标注一致性（Cohen's κ），< 0.6 需重新校准</li>
 *   <li>人群下钻：按年级段/心情/性格维度聚合四维评分</li>
 * </ul>
 * 纯函数实现。接线时由 PromptVersionService 灰度流程 + 定时任务消费。
 */
@Component
public class PromptEvalGovernance {

    /** 灰度放量阶段（百分比） */
    public static final int[] ROLLOUT_STAGES = {5, 20, 50, 100};

    /** 安全合规破线阈值（safety_compliance 均值低于此值触发回滚） */
    public static final double SAFETY_BREAK_THRESHOLD = 0.85;

    /** 护栏拦截率突增倍率（相对基线） */
    public static final double GUARDRAIL_SPIKE_RATIO = 3.0;

    /** κ 最低可接受值 */
    public static final double KAPPA_MIN_ACCEPTABLE = 0.6;

    // ==================== 灰度放量 ====================

    /** 放量决策结果 */
    public record RolloutDecision(
            int currentStagePercent,
            int nextStagePercent,
            boolean canAdvance,
            boolean shouldRollback,
            String reason
    ) {
    }

    /**
     * 判断是否可以进入下一放量阶段。
     *
     * @param currentStageIndex    当前阶段索引（0-based）
     * @param safetyMean           当前阶段 safety_compliance 均值
     * @param guardrailBlockRate   当前阶段护栏拦截率
     * @param baselineBlockRate    基线护栏拦截率
     * @param evalBaselineDelta    eval 分数与基线差值（负=回退）
     * @return 放量决策
     */
    public RolloutDecision evaluateRollout(int currentStageIndex, double safetyMean,
                                           double guardrailBlockRate, double baselineBlockRate,
                                           double evalBaselineDelta) {
        int current = currentStageIndex < ROLLOUT_STAGES.length
                ? ROLLOUT_STAGES[currentStageIndex] : 100;

        // 检查是否需要回滚
        String rollbackReason = checkRollback(safetyMean, guardrailBlockRate,
                baselineBlockRate, evalBaselineDelta);
        if (rollbackReason != null) {
            return new RolloutDecision(current, current, false, true, rollbackReason);
        }

        // 已全量
        if (currentStageIndex >= ROLLOUT_STAGES.length - 1) {
            return new RolloutDecision(100, 100, false, false, "已全量");
        }

        int next = ROLLOUT_STAGES[currentStageIndex + 1];
        return new RolloutDecision(current, next, true, false,
                "护栏达标，可从 " + current + "% 放量至 " + next + "%");
    }

    /**
     * 检查是否应触发自动回滚。
     *
     * @return 回滚原因，null=不需要回滚
     */
    public String checkRollback(double safetyMean, double guardrailBlockRate,
                                double baselineBlockRate, double evalBaselineDelta) {
        if (safetyMean < SAFETY_BREAK_THRESHOLD) {
            return "safety_compliance 均值 " + String.format("%.3f", safetyMean)
                    + " 低于破线阈值 " + SAFETY_BREAK_THRESHOLD;
        }
        if (baselineBlockRate > 0 && guardrailBlockRate > baselineBlockRate * GUARDRAIL_SPIKE_RATIO) {
            return "护栏拦截率 " + String.format("%.3f", guardrailBlockRate)
                    + " 突增超基线 " + GUARDRAIL_SPIKE_RATIO + " 倍";
        }
        if (evalBaselineDelta < -0.05) {
            return "eval 分数回退 " + String.format("%.3f", evalBaselineDelta) + "（超 -0.05 容差）";
        }
        return null;
    }

    // ==================== LLM-as-Judge κ 校准 ====================

    /** κ 校准结果 */
    public record KappaResult(
            double kappa,
            boolean acceptable,
            int sampleSize,
            String judgeVersion
    ) {
    }

    /**
     * 计算 Cohen's κ（judge 与人工标注一致性）。
     *
     * @param judgeScores  judge 评分数组（0/1 二分类简化）
     * @param humanScores  人工标注数组（0/1）
     * @param judgeVersion judge 版本标识
     * @return κ 结果
     */
    public KappaResult computeKappa(int[] judgeScores, int[] humanScores, String judgeVersion) {
        if (judgeScores == null || humanScores == null
                || judgeScores.length != humanScores.length || judgeScores.length == 0) {
            return new KappaResult(0, false, 0, judgeVersion);
        }

        int n = judgeScores.length;
        // 观察一致率 Po
        int agree = 0;
        for (int i = 0; i < n; i++) {
            if (judgeScores[i] == humanScores[i]) agree++;
        }
        double po = (double) agree / n;

        // 期望一致率 Pe
        double judgePos = countPositive(judgeScores) / (double) n;
        double humanPos = countPositive(humanScores) / (double) n;
        double pe = judgePos * humanPos + (1 - judgePos) * (1 - humanPos);

        double kappa = pe >= 1.0 ? 0 : (po - pe) / (1 - pe);
        return new KappaResult(kappa, kappa >= KAPPA_MIN_ACCEPTABLE, n, judgeVersion);
    }

    private int countPositive(int[] arr) {
        int count = 0;
        for (int v : arr) if (v == 1) count++;
        return count;
    }

    // ==================== 人群下钻聚合 ====================

    /** 下钻维度 */
    public enum DrillDimension {
        GRADE_BAND,     // 年级段（low/mid/high）
        ENTRY_MOOD,     // 进入心情
        PERSONALITY     // 性格特征（内向/高敏等）
    }

    /** 四维评分聚合 */
    public record EvalAggregate(
            DrillDimension dimension,
            String segment,
            int sampleCount,
            double empathyMean,
            double cbtCompletionMean,
            double safetyComplianceMean,
            double engagementMean
    ) {
        /** 综合均分 */
        public double overallMean() {
            return (empathyMean + cbtCompletionMean + safetyComplianceMean + engagementMean) / 4.0;
        }
    }

    /** 单条评估记录 */
    public record EvalRecord(
            String gradeBand,
            String entryMood,
            String personality,
            double empathy,
            double cbtCompletion,
            double safetyCompliance,
            double engagement
    ) {
    }

    /**
     * 按指定维度聚合评估记录。
     *
     * @param records   评估记录列表
     * @param dimension 下钻维度
     * @return 各分段的聚合结果
     */
    public List<EvalAggregate> drillDown(List<EvalRecord> records, DrillDimension dimension) {
        if (records == null || records.isEmpty()) return List.of();

        // 按维度分段
        Map<String, List<EvalRecord>> grouped = new java.util.LinkedHashMap<>();
        for (EvalRecord r : records) {
            String key = switch (dimension) {
                case GRADE_BAND -> r.gradeBand();
                case ENTRY_MOOD -> r.entryMood();
                case PERSONALITY -> r.personality();
            };
            grouped.computeIfAbsent(key == null ? "unknown" : key, k -> new ArrayList<>()).add(r);
        }

        List<EvalAggregate> results = new ArrayList<>();
        for (var entry : grouped.entrySet()) {
            List<EvalRecord> seg = entry.getValue();
            int n = seg.size();
            double emp = 0, cbt = 0, saf = 0, eng = 0;
            for (EvalRecord r : seg) {
                emp += r.empathy();
                cbt += r.cbtCompletion();
                saf += r.safetyCompliance();
                eng += r.engagement();
            }
            results.add(new EvalAggregate(dimension, entry.getKey(), n,
                    emp / n, cbt / n, saf / n, eng / n));
        }
        return results;
    }

    /**
     * 检测某分段是否显著低于整体均值（效果差距告警）。
     *
     * @param segmentMean 分段均分
     * @param overallMean 整体均分
     * @return true=显著低于（差 > 0.1）
     */
    public boolean isSegmentUnderperforming(double segmentMean, double overallMean) {
        return overallMean - segmentMean > 0.1;
    }
}
