package com.mindsafe.ai.risk;

import com.mindsafe.common.enums.RiskLevel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 风险评分计算器（RISK-203，design/04 §十/§18.4）
 * <p>
 * 纯函数、int 评分、可解释 reason_codes 供教师复核。
 * <ul>
 *   <li>评分用于普通风险排序与橙黄分流，不替代强制升级</li>
 *   <li>RED 入口永远是硬规则/强制升级，评分不得成为 RED 的必要条件</li>
 *   <li>分值用 int，避免浮点误差</li>
 * </ul>
 * <p>
 * C-SSRS 儿童适配（§18.4）：自伤/自杀类叠加三轴结构化抽取（意念/行为/时间），
 * 被动抽取不量表化问询，结果写入 risk_event.context 供画像消费。
 */
@Component
public class RiskScoreCalculator {

    // ==================== 评分输入 ====================

    /**
     * 评分因子输入（对齐 design/04 §十权重表）。
     *
     * @param categoryBaseScore 类别基础分 B（取最高命中类别，不累加）
     * @param intentWeight      意图权重 I（明确+15/含混+8/转述+5/无 0）
     * @param planWeight        计划权重 P（方法/工具/地点/时间/对象各+5，最高+20）
     * @param recencyWeight     近期性 R（正在/今天+15/7天+10/30天+5/历史 0）
     * @param actionWeight      行为证据 A（已实施+25/医学危险+20/第三方报告+10）
     * @param repetitionWeight  重复与趋势 T（连续3次+10/7天升级+10/多类共现+5）
     * @param protectiveWeight  保护因素 G（可信成人-5/愿求助-5/安全承诺-5/专业处置-10）
     * @param falsePositivePenalty 误报惩罚 F（引用/否定/虚构 -10~-30）
     * @param confidenceAdjustment 置信度调整系数（0.0-1.0，语义分类置信度）
     * @param forcedUpgrade     硬规则强制升级级别（null=无强制升级）
     * @param cssrsIdeation     C-SSRS 意念强度轴（null=非自伤类或未抽取）
     * @param cssrsBehavior     C-SSRS 行为轴（null=无行为证据）
     */
    public record ScoreInput(
            int categoryBaseScore,
            int intentWeight,
            int planWeight,
            int recencyWeight,
            int actionWeight,
            int repetitionWeight,
            int protectiveWeight,
            int falsePositivePenalty,
            double confidenceAdjustment,
            RiskLevel forcedUpgrade,
            CssrsIdeation cssrsIdeation,
            CssrsBehavior cssrsBehavior
    ) {
    }

    /** C-SSRS 意念强度轴（§18.4 三轴之一，被动抽取） */
    public enum CssrsIdeation {
        /** 死亡愿望（"不想活了"但无主动自伤意念） */
        DEATH_WISH(5),
        /** 主动自伤意念（"想伤害自己"） */
        ACTIVE_IDEATION(10),
        /** 有方法（"知道怎么做"） */
        WITH_METHOD(15),
        /** 有计划/意图（"已经想好什么时候/怎么做"） */
        WITH_PLAN_INTENT(20);

        private final int weight;

        CssrsIdeation(int weight) {
            this.weight = weight;
        }

        public int weight() {
            return weight;
        }
    }

    /** C-SSRS 行为轴（§18.4 三轴之二） */
    public enum CssrsBehavior {
        /** 准备行为（收集工具、写遗书等） */
        PREPARATORY(15),
        /** 中断的尝试（被阻止/自行停止） */
        INTERRUPTED_ATTEMPT(20),
        /** 实际尝试 */
        ACTUAL_ATTEMPT(25);

        private final int weight;

        CssrsBehavior(int weight) {
            this.weight = weight;
        }

        public int weight() {
            return weight;
        }
    }

    // ==================== 评分输出 ====================

    /**
     * 评分结果。
     *
     * @param score       最终分数（0-100）
     * @param level       风险级别（强制升级优先于阈值）
     * @param reasonCodes 可解释评分项列表（供教师复核/审计）
     */
    public record ScoreResult(int score, RiskLevel level, List<String> reasonCodes) {
    }

    // ==================== 核心计算 ====================

    /**
     * 计算风险分数与级别。
     * <p>
     * 铁律：forcedUpgrade != null 时级别取强制升级值（只升不降），分数仍计算（供排序）。
     */
    public ScoreResult calculate(ScoreInput input) {
        List<String> reasons = new ArrayList<>();

        // 基础分
        int base = input.categoryBaseScore();
        if (base > 0) reasons.add("B=" + base);

        // 各权重项
        int raw = base;
        raw = addWeight(raw, input.intentWeight(), "I", reasons);
        raw = addWeight(raw, input.planWeight(), "P", reasons);
        raw = addWeight(raw, input.recencyWeight(), "R", reasons);
        raw = addWeight(raw, input.actionWeight(), "A", reasons);
        raw = addWeight(raw, input.repetitionWeight(), "T", reasons);

        // C-SSRS 三轴加权（自伤/自杀类叠加）
        if (input.cssrsIdeation() != null) {
            raw += input.cssrsIdeation().weight();
            reasons.add("C-SSRS意念=" + input.cssrsIdeation().name() + "(+" + input.cssrsIdeation().weight() + ")");
        }
        if (input.cssrsBehavior() != null) {
            raw += input.cssrsBehavior().weight();
            reasons.add("C-SSRS行为=" + input.cssrsBehavior().name() + "(+" + input.cssrsBehavior().weight() + ")");
        }

        // 保护因素（负值）
        if (input.protectiveWeight() != 0) {
            raw += input.protectiveWeight();
            reasons.add("G=" + input.protectiveWeight());
        }
        // 误报惩罚（负值）
        if (input.falsePositivePenalty() != 0) {
            raw += input.falsePositivePenalty();
            reasons.add("F=" + input.falsePositivePenalty());
        }

        // 置信度调整 + clamp
        double adjusted = raw * Math.max(0.0, Math.min(1.0, input.confidenceAdjustment()));
        int score = clamp((int) Math.round(adjusted), 0, 100);
        reasons.add("conf=" + String.format("%.2f", input.confidenceAdjustment()));

        // 级别裁决：强制升级 > 阈值
        RiskLevel level;
        if (input.forcedUpgrade() != null) {
            level = input.forcedUpgrade();
            reasons.add("FORCED_UPGRADE=" + level.name());
        } else {
            level = threshold(score);
        }

        return new ScoreResult(score, level, reasons);
    }

    // ==================== 内部方法 ====================

    private int addWeight(int raw, int weight, String label, List<String> reasons) {
        if (weight != 0) {
            reasons.add(label + "=+" + weight);
            return raw + weight;
        }
        return raw;
    }

    /** 阈值分级（design/04 §十）：0-24 green, 25-49 yellow, 50-74 orange, 75-100 red */
    private RiskLevel threshold(int score) {
        if (score >= 75) return RiskLevel.RED;
        if (score >= 50) return RiskLevel.ORANGE;
        if (score >= 25) return RiskLevel.YELLOW;
        return RiskLevel.GREEN;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
