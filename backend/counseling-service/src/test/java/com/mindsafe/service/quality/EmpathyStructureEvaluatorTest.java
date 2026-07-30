package com.mindsafe.service.quality;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * EMP-201 共情「命名-确认-容纳」结构评估测试（design/52 §四）
 * <p>
 * 验证三段式命中检测、无效共情反模式识别、结构分计算与会话级聚合。
 */
class EmpathyStructureEvaluatorTest {

    private final EmpathyStructureEvaluator evaluator = new EmpathyStructureEvaluator();

    @Nested
    @DisplayName("单轮三段式检测")
    class SingleTurn {

        @Test
        @DisplayName("完整三段式（命名+确认+容纳）→ 3 步有效共情，满分")
        void fullThreeSteps() {
            var a = evaluator.evaluate("你看起来很难过，有这种感觉是很正常的，我会一直陪着你。");

            assertThat(a.namingPresent()).isTrue();
            assertThat(a.validationPresent()).isTrue();
            assertThat(a.containingPresent()).isTrue();
            assertThat(a.structureSteps()).isEqualTo(3);
            assertThat(a.antiPatterns()).isEmpty();
            assertThat(a.structureScore()).isCloseTo(1.0, within(0.001));
            assertThat(a.effectiveEmpathy()).isTrue();
        }

        @Test
        @DisplayName("仅命名 → 1 步，非有效共情")
        void namingOnly() {
            var a = evaluator.evaluate("听起来你有点生气。");

            assertThat(a.namingPresent()).isTrue();
            assertThat(a.validationPresent()).isFalse();
            assertThat(a.containingPresent()).isFalse();
            assertThat(a.structureSteps()).isEqualTo(1);
            assertThat(a.structureScore()).isCloseTo(0.333, within(0.001));
            assertThat(a.effectiveEmpathy()).isFalse();
        }

        @Test
        @DisplayName("命名+确认两段 → 2 步即判有效共情")
        void twoStepsEffective() {
            var a = evaluator.evaluate("你感到很害怕，这很正常。");

            assertThat(a.namingPresent()).isTrue();
            assertThat(a.validationPresent()).isTrue();
            assertThat(a.structureSteps()).isEqualTo(2);
            assertThat(a.effectiveEmpathy()).isTrue();
        }

        @Test
        @DisplayName("仅有情绪词无命名框架 → 不算命名")
        void emotionWordWithoutFrame() {
            var a = evaluator.evaluate("难过伤心害怕。");

            assertThat(a.namingPresent()).isFalse();
            assertThat(a.structureSteps()).isZero();
        }

        @Test
        @DisplayName("空文本与 null → 全零")
        void emptyAndNull() {
            assertThat(evaluator.evaluate("").structureScore()).isZero();
            assertThat(evaluator.evaluate(null).effectiveEmpathy()).isFalse();
            assertThat(evaluator.evaluate("   ").structureSteps()).isZero();
        }
    }

    @Nested
    @DisplayName("无效共情反模式")
    class AntiPatterns {

        @Test
        @DisplayName("廉价安慰（否认情绪）→ 命中并扣分")
        void cheapReassurance() {
            var a = evaluator.evaluate("别难过啦，没事的，开心点！");

            assertThat(a.antiPatterns()).contains("cheap_reassurance");
            assertThat(a.structureScore()).isZero();
            assertThat(a.effectiveEmpathy()).isFalse();
        }

        @Test
        @DisplayName("说教（居高临下）→ 命中")
        void preaching() {
            var a = evaluator.evaluate("你应该懂事一点，你必须听话。");

            assertThat(a.antiPatterns()).contains("preaching");
        }

        @Test
        @DisplayName("急于解决（过早给方案）→ 命中")
        void rushingToSolve() {
            var a = evaluator.evaluate("我建议你去跟老师说。");

            assertThat(a.antiPatterns()).contains("rushing_to_solve");
        }

        @Test
        @DisplayName("结构完整但含反模式 → 扣分且判无效")
        void goodStructureWithAntiPattern() {
            var a = evaluator.evaluate("你看起来很难过，这很正常，我陪着你。不过你应该想开点。");

            assertThat(a.structureSteps()).isEqualTo(3);
            assertThat(a.antiPatterns()).contains("preaching", "cheap_reassurance");
            // 1.0 - 2*0.2 = 0.6
            assertThat(a.structureScore()).isCloseTo(0.6, within(0.001));
            assertThat(a.effectiveEmpathy()).isFalse();
        }
    }

    @Nested
    @DisplayName("会话级聚合")
    class SessionSummary {

        @Test
        @DisplayName("多轮聚合 → 平均分/有效轮/反模式轮/有效率")
        void aggregate() {
            var summary = evaluator.summarizeSession(List.of(
                    "你看起来很难过，这很正常，我陪着你。",   // 有效, 1.0
                    "听起来你有点生气。",                     // 无效, 0.333
                    "别难过啦，没事的。"                       // 反模式, 0.0
            ));

            assertThat(summary.turnsEvaluated()).isEqualTo(3);
            assertThat(summary.effectiveTurns()).isEqualTo(1);
            assertThat(summary.antiPatternTurns()).isEqualTo(1);
            assertThat(summary.avgStructureScore()).isCloseTo(0.444, within(0.001));
            assertThat(summary.effectiveRatio()).isCloseTo(0.333, within(0.001));
        }

        @Test
        @DisplayName("空会话 → 全零")
        void emptySession() {
            var summary = evaluator.summarizeSession(List.of());

            assertThat(summary.turnsEvaluated()).isZero();
            assertThat(summary.avgStructureScore()).isZero();
            assertThat(summary.effectiveRatio()).isZero();
        }
    }
}
