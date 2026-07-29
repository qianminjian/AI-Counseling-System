package com.mindsafe.ai.risk;

import com.mindsafe.ai.risk.RiskScoreCalculator.CssrsBehavior;
import com.mindsafe.ai.risk.RiskScoreCalculator.CssrsIdeation;
import com.mindsafe.ai.risk.RiskScoreCalculator.ScoreInput;
import com.mindsafe.ai.risk.RiskScoreCalculator.ScoreResult;
import com.mindsafe.common.enums.RiskLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RiskScoreCalculator 单元测试（RISK-203，design/04 §十/§18.4）
 * <p>
 * 金标准：评分公式权重验证、阈值分级、强制升级铁律、C-SSRS 三轴叠加。
 */
class RiskScoreCalculatorTest {

    private final RiskScoreCalculator calc = new RiskScoreCalculator();

    private ScoreInputBuilder builder() {
        return new ScoreInputBuilder();
    }

    /** 简化构造器（默认全 0/null/1.0 置信度） */
    private static class ScoreInputBuilder {
        int base = 0, intent = 0, plan = 0, recency = 0, action = 0, repetition = 0;
        int protective = 0, falsePositive = 0;
        double conf = 1.0;
        RiskLevel forced = null;
        CssrsIdeation ideation = null;
        CssrsBehavior behavior = null;

        ScoreInputBuilder base(int v) { this.base = v; return this; }
        ScoreInputBuilder intent(int v) { this.intent = v; return this; }
        ScoreInputBuilder plan(int v) { this.plan = v; return this; }
        ScoreInputBuilder recency(int v) { this.recency = v; return this; }
        ScoreInputBuilder action(int v) { this.action = v; return this; }
        ScoreInputBuilder repetition(int v) { this.repetition = v; return this; }
        ScoreInputBuilder protective(int v) { this.protective = v; return this; }
        ScoreInputBuilder falsePositive(int v) { this.falsePositive = v; return this; }
        ScoreInputBuilder conf(double v) { this.conf = v; return this; }
        ScoreInputBuilder forced(RiskLevel v) { this.forced = v; return this; }
        ScoreInputBuilder ideation(CssrsIdeation v) { this.ideation = v; return this; }
        ScoreInputBuilder behavior(CssrsBehavior v) { this.behavior = v; return this; }

        ScoreInput build() {
            return new ScoreInput(base, intent, plan, recency, action, repetition,
                    protective, falsePositive, conf, forced, ideation, behavior);
        }
    }

    @Test
    @DisplayName("零分 → GREEN")
    void zeroScore_green() {
        ScoreResult r = calc.calculate(builder().build());
        assertThat(r.score()).isEqualTo(0);
        assertThat(r.level()).isEqualTo(RiskLevel.GREEN);
    }

    @Test
    @DisplayName("霸凌基础分 35 → YELLOW（25-49）")
    void bullying_yellow() {
        ScoreResult r = calc.calculate(builder().base(35).build());
        assertThat(r.score()).isEqualTo(35);
        assertThat(r.level()).isEqualTo(RiskLevel.YELLOW);
    }

    @Test
    @DisplayName("自伤基础 60 + 意图 15 = 75 → RED 阈值")
    void selfHarm_threshold_red() {
        ScoreResult r = calc.calculate(builder().base(60).intent(15).build());
        assertThat(r.score()).isEqualTo(75);
        assertThat(r.level()).isEqualTo(RiskLevel.RED);
    }

    @Test
    @DisplayName("强制升级 RED：即使分数低也取 RED（铁律）")
    void forcedUpgrade_overrides_threshold() {
        ScoreResult r = calc.calculate(builder().base(10).forced(RiskLevel.RED).build());
        assertThat(r.score()).isEqualTo(10);
        assertThat(r.level()).isEqualTo(RiskLevel.RED);
        assertThat(r.reasonCodes()).anyMatch(s -> s.contains("FORCED_UPGRADE"));
    }

    @Test
    @DisplayName("保护因素降级：60-10=50 → ORANGE（非 RED）")
    void protective_reduces() {
        ScoreResult r = calc.calculate(builder().base(60).protective(-10).build());
        assertThat(r.score()).isEqualTo(50);
        assertThat(r.level()).isEqualTo(RiskLevel.ORANGE);
    }

    @Test
    @DisplayName("误报惩罚：35-30=5 → GREEN")
    void falsePositive_penalty() {
        ScoreResult r = calc.calculate(builder().base(35).falsePositive(-30).build());
        assertThat(r.score()).isEqualTo(5);
        assertThat(r.level()).isEqualTo(RiskLevel.GREEN);
    }

    @Test
    @DisplayName("置信度 0.5 减半：base 60 × 0.5 = 30 → YELLOW")
    void confidence_halves() {
        ScoreResult r = calc.calculate(builder().base(60).conf(0.5).build());
        assertThat(r.score()).isEqualTo(30);
        assertThat(r.level()).isEqualTo(RiskLevel.YELLOW);
    }

    @Test
    @DisplayName("C-SSRS 意念 WITH_PLAN_INTENT(+20) 叠加：base 60 + 20 = 80 → RED")
    void cssrs_ideation_stacks() {
        ScoreResult r = calc.calculate(builder().base(60).ideation(CssrsIdeation.WITH_PLAN_INTENT).build());
        assertThat(r.score()).isEqualTo(80);
        assertThat(r.level()).isEqualTo(RiskLevel.RED);
        assertThat(r.reasonCodes()).anyMatch(s -> s.contains("C-SSRS意念"));
    }

    @Test
    @DisplayName("C-SSRS 行为 ACTUAL_ATTEMPT(+25)：base 55 + 25 = 80 → RED")
    void cssrs_behavior_stacks() {
        ScoreResult r = calc.calculate(builder().base(55).behavior(CssrsBehavior.ACTUAL_ATTEMPT).build());
        assertThat(r.score()).isEqualTo(80);
        assertThat(r.level()).isEqualTo(RiskLevel.RED);
    }

    @Test
    @DisplayName("clamp 上限 100：多项叠加不超 100")
    void clamp_max_100() {
        ScoreResult r = calc.calculate(builder()
                .base(60).intent(15).plan(20).recency(15).action(25)
                .ideation(CssrsIdeation.WITH_PLAN_INTENT)
                .behavior(CssrsBehavior.ACTUAL_ATTEMPT)
                .build());
        assertThat(r.score()).isEqualTo(100);
    }

    @Test
    @DisplayName("clamp 下限 0：大额误报惩罚不负")
    void clamp_min_0() {
        ScoreResult r = calc.calculate(builder().base(10).falsePositive(-30).build());
        assertThat(r.score()).isEqualTo(0);
        assertThat(r.level()).isEqualTo(RiskLevel.GREEN);
    }

    @Test
    @DisplayName("reason_codes 可解释：含所有非零项")
    void reasonCodes_explainable() {
        ScoreResult r = calc.calculate(builder().base(35).intent(15).recency(10).protective(-5).build());
        assertThat(r.reasonCodes()).contains("B=35", "I=+15", "R=+10", "G=-5");
    }

    @Test
    @DisplayName("阈值边界：49→YELLOW, 50→ORANGE, 74→ORANGE, 75→RED")
    void threshold_boundaries() {
        assertThat(calc.calculate(builder().base(49).build()).level()).isEqualTo(RiskLevel.YELLOW);
        assertThat(calc.calculate(builder().base(50).build()).level()).isEqualTo(RiskLevel.ORANGE);
        assertThat(calc.calculate(builder().base(74).build()).level()).isEqualTo(RiskLevel.ORANGE);
        assertThat(calc.calculate(builder().base(75).build()).level()).isEqualTo(RiskLevel.RED);
    }
}
