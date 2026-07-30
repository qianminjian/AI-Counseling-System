package com.mindsafe.service.assessment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AssessmentScoringEngine 金标准测试（SCALE-001，design/34 §十 M1 验收）
 * <p>
 * 用 PHQ-A/GAD-7 官方计分示例验证：总分 100% 与人工计分一致；
 * critical 条目 ≥ 阈值 → S0 即时熔断；分档 cut-off 与原始文献完全对齐。
 */
class AssessmentScoringEngineTest {

    private AssessmentScoringEngine engine;

    @BeforeEach
    void setUp() {
        engine = new AssessmentScoringEngine();
    }

    /** 构造 PHQ-A 9 题作答（每题 0-3） */
    private Map<String, Integer> phqaResponses(int... values) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < values.length && i < 9; i++) {
            map.put("phqa_" + (i + 1), values[i]);
        }
        return map;
    }

    /** 构造 GAD-7 7 题作答（每题 0-3） */
    private Map<String, Integer> gad7Responses(int... values) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < values.length && i < 7; i++) {
            map.put("gad7_" + (i + 1), values[i]);
        }
        return map;
    }

    // ==================== PHQ-A 分档金标准（Johnson 2002 cut-off） ====================

    @Nested
    @DisplayName("PHQ-A 分档计分")
    class PhqaScoring {

        @Test
        @DisplayName("全 0 → 总分 0，无明显症状，无预警")
        void allZero_none() {
            ScoringResult r = engine.score(BuiltinScales.PHQA_SCORING_RULES,
                    phqaResponses(0, 0, 0, 0, 0, 0, 0, 0, 0), Set.of());

            assertThat(r.totalScore()).isEqualTo(0);
            assertThat(r.bandLevel()).isEqualTo("none");
            assertThat(r.bandLabel()).isEqualTo("无明显症状");
            assertThat(r.alertLevel()).isNull();
            assertThat(r.criticalTriggered()).isFalse();
        }

        @Test
        @DisplayName("总分 5（边界）→ 轻度，S3")
        void score5_mild() {
            // 5 题各 1 分，4 题 0 分
            ScoringResult r = engine.score(BuiltinScales.PHQA_SCORING_RULES,
                    phqaResponses(1, 1, 1, 1, 1, 0, 0, 0, 0), Set.of());

            assertThat(r.totalScore()).isEqualTo(5);
            assertThat(r.bandLevel()).isEqualTo("mild");
            assertThat(r.alertLevel()).isEqualTo("S3");
        }

        @Test
        @DisplayName("总分 9（mild 上界）→ 仍为轻度 S3")
        void score9_mildUpperBound() {
            ScoringResult r = engine.score(BuiltinScales.PHQA_SCORING_RULES,
                    phqaResponses(1, 1, 1, 1, 1, 1, 1, 1, 1), Set.of());

            assertThat(r.totalScore()).isEqualTo(9);
            assertThat(r.bandLevel()).isEqualTo("mild");
            assertThat(r.alertLevel()).isEqualTo("S3");
        }

        @Test
        @DisplayName("总分 10（moderate 下界）→ 中度，S2")
        void score10_moderate() {
            // 1 题 2 分 + 8 题 1 分 = 10
            ScoringResult r = engine.score(BuiltinScales.PHQA_SCORING_RULES,
                    phqaResponses(2, 1, 1, 1, 1, 1, 1, 1, 1), Set.of());

            assertThat(r.totalScore()).isEqualTo(10);
            assertThat(r.bandLevel()).isEqualTo("moderate");
            assertThat(r.alertLevel()).isEqualTo("S2");
        }

        @Test
        @DisplayName("总分 15 → 中重度，S1")
        void score15_modSevere() {
            // 6 题 2 分 + 3 题 1 分 = 15
            ScoringResult r = engine.score(BuiltinScales.PHQA_SCORING_RULES,
                    phqaResponses(2, 2, 2, 2, 2, 2, 1, 1, 1), Set.of());

            assertThat(r.totalScore()).isEqualTo(15);
            assertThat(r.bandLevel()).isEqualTo("mod_severe");
            assertThat(r.alertLevel()).isEqualTo("S1");
        }

        @Test
        @DisplayName("总分 20 → 重度，S1")
        void score20_severe() {
            // 2 题 3 分 + 7 题 2 分 = 20
            ScoringResult r = engine.score(BuiltinScales.PHQA_SCORING_RULES,
                    phqaResponses(3, 3, 2, 2, 2, 2, 2, 2, 2), Set.of());

            assertThat(r.totalScore()).isEqualTo(20);
            assertThat(r.bandLevel()).isEqualTo("severe");
            assertThat(r.alertLevel()).isEqualTo("S1");
        }

        @Test
        @DisplayName("满分 27（全 3）→ 重度上界，S1")
        void maxScore27_severe() {
            ScoringResult r = engine.score(BuiltinScales.PHQA_SCORING_RULES,
                    phqaResponses(3, 3, 3, 3, 3, 3, 3, 3, 3), Set.of());

            assertThat(r.totalScore()).isEqualTo(27);
            assertThat(r.bandLevel()).isEqualTo("severe");
            assertThat(r.alertLevel()).isEqualTo("S1");
        }

        @Test
        @DisplayName("维度分与总分一致（单维度量表）")
        void dimensionScore_matchesTotal() {
            ScoringResult r = engine.score(BuiltinScales.PHQA_SCORING_RULES,
                    phqaResponses(2, 1, 1, 1, 1, 1, 1, 1, 1), Set.of());

            assertThat(r.dimensionScores()).containsEntry("depression", 10);
        }
    }

    // ==================== PHQ-A 关键条目熔断（design/34 §六） ====================

    @Nested
    @DisplayName("PHQ-A 关键条目即时熔断")
    class PhqaCritical {

        @Test
        @DisplayName("phqa_9=1（有几天）→ S0 熔断，即使总分仅 1 分")
        void criticalItem_triggersS0() {
            // 只有第 9 题 = 1，其余全 0 → 总分 1（本应无预警），但 critical 触发 S0
            ScoringResult r = engine.score(BuiltinScales.PHQA_SCORING_RULES,
                    phqaResponses(0, 0, 0, 0, 0, 0, 0, 0, 1),
                    BuiltinScales.PHQA_CRITICAL_ITEMS);

            assertThat(r.totalScore()).isEqualTo(1);
            assertThat(r.criticalTriggered()).isTrue();
            assertThat(r.criticalItemIds()).containsExactly("phqa_9");
            assertThat(r.alertLevel()).isEqualTo("S0");
            assertThat(r.isS0()).isTrue();
            // 分档仍按总分计算（记录用），但预警被 S0 覆盖
            assertThat(r.bandLevel()).isEqualTo("none");
        }

        @Test
        @DisplayName("phqa_9=3（几乎每天）→ S0 熔断")
        void criticalItem_maxValue_triggersS0() {
            ScoringResult r = engine.score(BuiltinScales.PHQA_SCORING_RULES,
                    phqaResponses(1, 1, 1, 1, 1, 1, 1, 1, 3),
                    BuiltinScales.PHQA_CRITICAL_ITEMS);

            assertThat(r.criticalTriggered()).isTrue();
            assertThat(r.alertLevel()).isEqualTo("S0");
            assertThat(r.totalScore()).isEqualTo(11);
        }

        @Test
        @DisplayName("phqa_9=0（完全没有）→ 不触发熔断，正常分档")
        void criticalItem_zero_noTrigger() {
            ScoringResult r = engine.score(BuiltinScales.PHQA_SCORING_RULES,
                    phqaResponses(2, 2, 2, 2, 2, 2, 2, 2, 0),
                    BuiltinScales.PHQA_CRITICAL_ITEMS);

            assertThat(r.criticalTriggered()).isFalse();
            assertThat(r.totalScore()).isEqualTo(16);
            assertThat(r.alertLevel()).isEqualTo("S1");
        }

        @Test
        @DisplayName("无 critical 条目配置（空集）→ 即使 phqa_9>0 也不熔断")
        void noCriticalConfig_noTrigger() {
            ScoringResult r = engine.score(BuiltinScales.PHQA_SCORING_RULES,
                    phqaResponses(0, 0, 0, 0, 0, 0, 0, 0, 2), Set.of());

            assertThat(r.criticalTriggered()).isFalse();
            assertThat(r.alertLevel()).isNull();
        }
    }

    // ==================== GAD-7 分档金标准（Spitzer 2006 cut-off） ====================

    @Nested
    @DisplayName("GAD-7 分档计分")
    class Gad7Scoring {

        @Test
        @DisplayName("全 0 → 总分 0，无/极轻，无预警")
        void allZero_minimal() {
            ScoringResult r = engine.score(BuiltinScales.GAD7_SCORING_RULES,
                    gad7Responses(0, 0, 0, 0, 0, 0, 0), Set.of());

            assertThat(r.totalScore()).isEqualTo(0);
            assertThat(r.bandLevel()).isEqualTo("minimal");
            assertThat(r.alertLevel()).isNull();
        }

        @Test
        @DisplayName("总分 7 → 轻度，S3")
        void score7_mild() {
            ScoringResult r = engine.score(BuiltinScales.GAD7_SCORING_RULES,
                    gad7Responses(1, 1, 1, 1, 1, 1, 1), Set.of());

            assertThat(r.totalScore()).isEqualTo(7);
            assertThat(r.bandLevel()).isEqualTo("mild");
            assertThat(r.alertLevel()).isEqualTo("S3");
        }

        @Test
        @DisplayName("总分 10 → 中度，S2")
        void score10_moderate() {
            // 3 题 2 分 + 4 题 1 分 = 10
            ScoringResult r = engine.score(BuiltinScales.GAD7_SCORING_RULES,
                    gad7Responses(2, 2, 2, 1, 1, 1, 1), Set.of());

            assertThat(r.totalScore()).isEqualTo(10);
            assertThat(r.bandLevel()).isEqualTo("moderate");
            assertThat(r.alertLevel()).isEqualTo("S2");
        }

        @Test
        @DisplayName("总分 15 → 重度，S1")
        void score15_severe() {
            // 1 题 3 分 + 6 题 2 分 = 15
            ScoringResult r = engine.score(BuiltinScales.GAD7_SCORING_RULES,
                    gad7Responses(3, 2, 2, 2, 2, 2, 2), Set.of());

            assertThat(r.totalScore()).isEqualTo(15);
            assertThat(r.bandLevel()).isEqualTo("severe");
            assertThat(r.alertLevel()).isEqualTo("S1");
        }

        @Test
        @DisplayName("满分 21（全 3）→ 重度上界")
        void maxScore21_severe() {
            ScoringResult r = engine.score(BuiltinScales.GAD7_SCORING_RULES,
                    gad7Responses(3, 3, 3, 3, 3, 3, 3), Set.of());

            assertThat(r.totalScore()).isEqualTo(21);
            assertThat(r.bandLevel()).isEqualTo("severe");
            assertThat(r.alertLevel()).isEqualTo("S1");
        }

        @Test
        @DisplayName("GAD-7 无 critical 条目 → 不触发熔断")
        void noCriticalItems() {
            ScoringResult r = engine.score(BuiltinScales.GAD7_SCORING_RULES,
                    gad7Responses(3, 3, 3, 3, 3, 3, 3), BuiltinScales.GAD7_CRITICAL_ITEMS);

            assertThat(r.criticalTriggered()).isFalse();
        }
    }

    // ==================== 边界与异常 ====================

    @Nested
    @DisplayName("边界与异常处理")
    class EdgeCases {

        @Test
        @DisplayName("空作答 → 总分 0，正常分档")
        void emptyResponses_zeroScore() {
            ScoringResult r = engine.score(BuiltinScales.PHQA_SCORING_RULES, Map.of(), Set.of());

            assertThat(r.totalScore()).isEqualTo(0);
            assertThat(r.bandLevel()).isEqualTo("none");
        }

        @Test
        @DisplayName("null 作答 → 等同空 map")
        void nullResponses_zeroScore() {
            ScoringResult r = engine.score(BuiltinScales.PHQA_SCORING_RULES, null, null);

            assertThat(r.totalScore()).isEqualTo(0);
            assertThat(r.criticalTriggered()).isFalse();
        }

        @Test
        @DisplayName("scoring_rules 为 null → IllegalArgumentException")
        void nullRules_throws() {
            assertThatThrownBy(() -> engine.score(null, Map.of(), Set.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不能为空");
        }

        @Test
        @DisplayName("scoring_rules 非法 JSON → IllegalArgumentException")
        void invalidJson_throws() {
            assertThatThrownBy(() -> engine.score("{invalid", Map.of(), Set.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("解析失败");
        }

        @Test
        @DisplayName("scoring_rules 缺少 bands → IllegalArgumentException")
        void missingBands_throws() {
            assertThatThrownBy(() -> engine.score("{\"method\":\"sum\"}", Map.of(), Set.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("bands");
        }

        @Test
        @DisplayName("部分作答（中途熔断后剩余题未答）→ 只计已答题分数")
        void partialResponses_sumAnsweredOnly() {
            // 只答了 3 题（模拟熔断后静默终止）
            Map<String, Integer> partial = Map.of("phqa_1", 2, "phqa_2", 1, "phqa_9", 1);
            ScoringResult r = engine.score(BuiltinScales.PHQA_SCORING_RULES,
                    partial, BuiltinScales.PHQA_CRITICAL_ITEMS);

            assertThat(r.totalScore()).isEqualTo(4);
            assertThat(r.criticalTriggered()).isTrue();
            assertThat(r.alertLevel()).isEqualTo("S0");
        }
    }
}
