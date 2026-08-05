package com.mindsafe.ai.orchestrator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EmotionOrchestrationEvaluator 单元测试（13/20 篇审计补齐：ai.orchestrator→80%）
 * 覆盖：稳定回落速度、会话深度、情绪×策略适配判定、适配组 vs 不适配组效果对比
 */
class EmotionOrchestrationEvaluatorTest {

    private final EmotionOrchestrationEvaluator evaluator = new EmotionOrchestrationEvaluator();

    @Nested
    @DisplayName("measureRecovery 稳定回落速度")
    class Recovery {

        @Test
        @DisplayName("null/空序列 → 未恢复，unknown 进出情绪")
        void emptyInput() {
            EmotionOrchestrationEvaluator.RecoveryResult r1 = evaluator.measureRecovery(null);
            assertThat(r1.recovered()).isFalse();
            assertThat(r1.turnsToStable()).isZero();
            assertThat(r1.entryEmotion()).isEqualTo("unknown");

            EmotionOrchestrationEvaluator.RecoveryResult r2 = evaluator.measureRecovery(List.of());
            assertThat(r2.recovered()).isFalse();
        }

        @Test
        @DisplayName("ACTIVATED→STABLE：返回首次稳定的轮数与进出情绪")
        void recoveredAfterTurns() {
            EmotionOrchestrationEvaluator.RecoveryResult r = evaluator.measureRecovery(
                    List.of("ACTIVATED", "ACTIVATED", "STABLE", "STABLE"));

            assertThat(r.recovered()).isTrue();
            assertThat(r.turnsToStable()).isEqualTo(2);
            assertThat(r.entryEmotion()).isEqualTo("ACTIVATED");
            assertThat(r.finalEmotion()).isEqualTo("STABLE");
        }

        @Test
        @DisplayName("大小写不敏感（stable）")
        void caseInsensitive() {
            EmotionOrchestrationEvaluator.RecoveryResult r = evaluator.measureRecovery(
                    List.of("ACTIVATED", "stable"));
            assertThat(r.recovered()).isTrue();
            assertThat(r.turnsToStable()).isEqualTo(1);
        }

        @Test
        @DisplayName("从未稳定 → 未恢复，轮数取序列长度")
        void neverStable() {
            EmotionOrchestrationEvaluator.RecoveryResult r = evaluator.measureRecovery(
                    List.of("ACTIVATED", "ACTIVATED", "CRISIS"));

            assertThat(r.recovered()).isFalse();
            assertThat(r.turnsToStable()).isEqualTo(3);
            assertThat(r.finalEmotion()).isEqualTo("CRISIS");
        }
    }

    @Nested
    @DisplayName("measureDepth 会话深度")
    class Depth {

        @Test
        @DisplayName("null → 0；过滤单字/短词/null 轮")
        void filtersMeaninglessTurns() {
            assertThat(evaluator.measureDepth(null)).isZero();

            List<String> messages = Arrays.asList(
                    "今天被同学嘲笑了",  // 有效
                    "嗯",                // 单字无效
                    "   ",               // 空白无效
                    null,                // null 无效
                    "我其实很难过");      // 有效
            assertThat(evaluator.measureDepth(messages)).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("assessFit 适配判定")
    class Fit {

        @Test
        @DisplayName("信息不足（任一为 null）→ 不适配")
        void insufficientInfo() {
            assertThat(evaluator.assessFit(null, "empathy").adapted()).isFalse();
            assertThat(evaluator.assessFit("sad", null).adapted()).isFalse();
        }

        @Test
        @DisplayName("sad/scared × empathy|mirror → 适配；其他策略 → 不适配")
        void sadRules() {
            assertThat(evaluator.assessFit("sad", "empathy_mirror").adapted()).isTrue();
            assertThat(evaluator.assessFit("scared", "MIRROR").adapted()).isTrue();
            assertThat(evaluator.assessFit("sad", "grounding").adapted()).isFalse();
        }

        @Test
        @DisplayName("angry × neutral|naming；anxious × grounding|breathing；happy × positive|extend")
        void otherEmotionRules() {
            assertThat(evaluator.assessFit("angry", "neutral_nonjudgment").adapted()).isTrue();
            assertThat(evaluator.assessFit("angry", "emotion_naming").adapted()).isTrue();
            assertThat(evaluator.assessFit("angry", "empathy").adapted()).isFalse();

            assertThat(evaluator.assessFit("anxious", "grounding_technique").adapted()).isTrue();
            assertThat(evaluator.assessFit("nervous", "deep_breathing").adapted()).isTrue();

            assertThat(evaluator.assessFit("happy", "positive_extend").adapted()).isTrue();
        }

        @Test
        @DisplayName("未知情绪不判不适配（默认适配）")
        void unknownEmotionDefaultsAdapted() {
            assertThat(evaluator.assessFit("confused", "whatever").adapted()).isTrue();
        }
    }

    @Nested
    @DisplayName("compare 效果对比")
    class Compare {

        @Test
        @DisplayName("适配组全面占优（≥2 信号）→ 有效")
        void effective() {
            EmotionOrchestrationEvaluator.EffectComparison c = evaluator.compare(
                    List.of(2.0, 3.0), List.of(6.0, 7.0),   // 回落更快
                    List.of(8.0, 9.0), List.of(4.0, 5.0),   // 深度更深（提升 >10%）
                    List.of(0.9, 1.0), List.of(0.5, 0.6));  // 满意度更高

            assertThat(c.emotionAdaptationEffective()).isTrue();
            assertThat(c.adaptedRecoveryMean()).isEqualTo(2.5);
            assertThat(c.nonAdaptedRecoveryMean()).isEqualTo(6.5);
        }

        @Test
        @DisplayName("仅一项占优 → 无效")
        void ineffective() {
            EmotionOrchestrationEvaluator.EffectComparison c = evaluator.compare(
                    List.of(2.0), List.of(6.0),       // 回落更快（仅 1 项）
                    List.of(5.0), List.of(5.0),       // 深度持平
                    List.of(0.5), List.of(0.5));      // 满意度持平

            assertThat(c.emotionAdaptationEffective()).isFalse();
        }

        @Test
        @DisplayName("空列表 → 均值 0，除零保护不误判有效")
        void emptyLists() {
            EmotionOrchestrationEvaluator.EffectComparison c = evaluator.compare(
                    List.of(), List.of(), null, null, List.of(), null);

            assertThat(c.adaptedRecoveryMean()).isZero();
            assertThat(c.nonAdaptedDepthMean()).isZero();
            // 全部均值为 0：无正向信号（回落 0<0 不成立；深度/满意度分母为 0 跳过）
            assertThat(c.emotionAdaptationEffective()).isFalse();
        }
    }
}
