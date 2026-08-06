package com.mindsafe.ai.risk;

import com.mindsafe.ai.risk.EmotionVocabulary.Category;
import com.mindsafe.ai.risk.RiskKeywordRegistry.Level;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 风险知识规则源一致性断言（ARCH-003，doing/63 §3.3）。
 * <p>
 * 把「集合一致」漂移挡在 CI：四级词典互斥、情绪三分类完备、关键信号跨管线结论一致。
 * 规则源只读断言见 RiskKeywordRegistryTest / EmotionVocabularyTest 的 ReadOnly 组。
 */
class RiskRegistryConsistencyTest {

    @Nested
    @DisplayName("四级词典互斥（RED ∩ ORANGE ∩ YELLOW = ∅）")
    class DictionaryDisjointness {

        @Test
        @DisplayName("RED_HARD 与 ORANGE 无交集")
        void redHardDisjointOrange() {
            assertThat(RiskKeywordRegistry.RED_HARD)
                    .doesNotContainAnyElementsOf(RiskKeywordRegistry.ORANGE);
        }

        @Test
        @DisplayName("RED_HARD 与 YELLOW 无交集")
        void redHardDisjointYellow() {
            assertThat(RiskKeywordRegistry.RED_HARD)
                    .doesNotContainAnyElementsOf(RiskKeywordRegistry.YELLOW);
        }

        @Test
        @DisplayName("ORANGE 与 YELLOW 无交集")
        void orangeDisjointYellow() {
            assertThat(RiskKeywordRegistry.ORANGE)
                    .doesNotContainAnyElementsOf(RiskKeywordRegistry.YELLOW);
        }

        @Test
        @DisplayName("高危方法词与准备词必须同时为 RED 硬规则词（同源同步；吃药为含混工具词例外）")
        void methodWordsAreAlsoGradeWords() {
            // 跳楼/上吊/割腕/带刀 高危方法词必须为 RED 硬规则；
            // "吃药" 为含混工具词（原设计：需语境判断，仅存于 SELF_HARM_METHOD）
            assertThat(RiskKeywordRegistry.RED_HARD)
                    .contains("跳楼", "上吊", "割腕", "带刀");
            assertThat(RiskKeywordRegistry.RED_HARD)
                    .containsAll(RiskKeywordRegistry.PREPARATORY_KEYWORDS);
        }
    }

    @Nested
    @DisplayName("情绪集合三分类完备（NEGATIVE ∩ POSITIVE = ∅；UNKNOWN 兜底）")
    class EmotionCompleteness {

        @Test
        @DisplayName("负面与正面权威成员互斥")
        void negativeDisjointPositive() {
            assertThat(EmotionVocabulary.NEGATIVE_KEYS)
                    .doesNotContainAnyElementsOf(EmotionVocabulary.POSITIVE_KEYS);
        }

        @Test
        @DisplayName("负面中文别名与正面中文别名互斥")
        void negativeChineseDisjointPositiveChinese() {
            assertThat(EmotionVocabulary.NEGATIVE_CHINESE)
                    .doesNotContainAnyElementsOf(EmotionVocabulary.POSITIVE_CHINESE);
        }

        @Test
        @DisplayName("任何 key 分类结果必然落于三分类（无遗漏路径）")
        void classifyAlwaysComplete() {
            for (String key : EmotionVocabulary.NEGATIVE_KEYS) {
                assertThat(EmotionVocabulary.classify(key)).isEqualTo(Category.NEGATIVE);
            }
            for (String key : EmotionVocabulary.POSITIVE_KEYS) {
                assertThat(EmotionVocabulary.classify(key)).isEqualTo(Category.POSITIVE);
            }
            assertThat(EmotionVocabulary.classify("unknown_key_xyz")).isEqualTo(Category.UNKNOWN);
        }
    }

    @Nested
    @DisplayName("关键信号跨管线一致（doing/63 §3.3 抽样）")
    class CrossPipelineConsistency {

        @ParameterizedTest
        @ValueSource(strings = {"anxious", "sad", "fearful", "angry", "disgusted"})
        @DisplayName("负面情绪信号在全部 6 个情绪消费管线入口结论一致")
        void negativeEmotionConsistent(String emotion) {
            // 6 个消费点入口（ARCH-003 调研清单，2026-08-06）：
            // SessionState.consecutiveNegativeCount / ConversationRiskProcessor.isNegativeEmotion /
            // SessionEndAnalyticsService.isNegative / VoiceEmotionTrendAnalyzer.isNegative /
            // ConversationContextAgent.isNegative / LongTermMemoryService.isNegativeEmotion
            assertThat(EmotionVocabulary.isNegative(emotion))
                    .as("情绪信号 %s 在各管线必须一致判定为负面", emotion)
                    .isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"想死", "割腕", "跳楼", "遗书"})
        @DisplayName("红色硬信号在风险判定入口结论一致")
        void redSignalConsistent(String signal) {
            assertThat(RiskKeywordRegistry.matchLevel(signal))
                    .as("风险信号 %s 必须一致命中 RED_HARD", signal)
                    .isEqualTo(Level.RED_HARD);
            // 评分入口一致（行为零变更锚点）
            assertThat(RiskKeywordRegistry.scoreFor(signal)).isEqualTo(RiskKeywordRegistry.SCORE_HARD);
        }

        @ParameterizedTest
        @ValueSource(strings = {"被欺负", "打我", "离家出走"})
        @DisplayName("橙色信号在风险判定入口结论一致")
        void orangeSignalConsistent(String signal) {
            assertThat(RiskKeywordRegistry.matchLevel(signal)).isEqualTo(Level.ORANGE);
        }

        @Test
        @DisplayName("意图/方法/准备词与评分因子抽取一致（C-SSRS 行为轴）")
        void methodConsistentWithScoreFactors() {
            assertThat(RiskKeywordRegistry.matchMethod("我想死，写了遗书，准备跳楼"))
                    .contains("想死", "遗书", "跳楼");
        }
    }
}
