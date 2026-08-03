package com.mindsafe.ai.voice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VoiceAnalysisResult 纯逻辑单测（AI-007 / 54_语音情感分析设计方案）。
 * <p>
 * 覆盖：有效情绪判定（置信度阈值 0.6 + 排除 unknown/other）/ 消极情绪四标签 /
 * 情绪风险辅助分级（fearful、sad→2 橙色；angry、disgusted→1 黄色）。
 */
@DisplayName("语音分析结果判定")
class VoiceAnalysisResultTest {

    private static VoiceAnalysisResult result(String labelEn, double confidence) {
        return new VoiceAnalysisResult(
                "测试文本",
                new VoiceAnalysisResult.EmotionInfo("标签", labelEn, confidence, List.of(0.1, 0.2)),
                1.0);
    }

    @Nested
    @DisplayName("hasValidEmotion 有效情绪")
    class ValidEmotion {

        @Test
        @DisplayName("emotion 为 null → 无效")
        void nullEmotion_invalid() {
            assertThat(new VoiceAnalysisResult("文本", null, 1.0).hasValidEmotion()).isFalse();
        }

        @Test
        @DisplayName("置信度恰为 0.6 → 无效（须严格大于阈值）")
        void confidenceAtThreshold_invalid() {
            assertThat(result("sad", 0.6).hasValidEmotion()).isFalse();
        }

        @Test
        @DisplayName("置信度 0.61 + 正常标签 → 有效")
        void aboveThreshold_valid() {
            assertThat(result("happy", 0.61).hasValidEmotion()).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"unknown", "other"})
        @DisplayName("unknown/other 标签无论置信度多高 → 无效")
        void excludedLabels_invalid(String labelEn) {
            assertThat(result(labelEn, 0.99).hasValidEmotion()).isFalse();
        }
    }

    @Nested
    @DisplayName("isNegativeEmotion 消极情绪")
    class NegativeEmotion {

        @ParameterizedTest
        @ValueSource(strings = {"sad", "fearful", "angry", "disgusted"})
        @DisplayName("四消极标签 → true")
        void negativeLabels(String labelEn) {
            assertThat(result(labelEn, 0.9).isNegativeEmotion()).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"happy", "neutral", "surprised"})
        @DisplayName("非消极标签 → false")
        void positiveLabels(String labelEn) {
            assertThat(result(labelEn, 0.9).isNegativeEmotion()).isFalse();
        }

        @Test
        @DisplayName("无效情绪（低置信度）即使是 sad → false")
        void invalidEmotion_false() {
            assertThat(result("sad", 0.5).isNegativeEmotion()).isFalse();
        }
    }

    @Nested
    @DisplayName("emotionRiskLevel 风险辅助分级")
    class RiskLevel {

        @ParameterizedTest
        @CsvSource({"fearful,2", "sad,2", "angry,1", "disgusted,1", "happy,0", "neutral,0"})
        @DisplayName("标签 → 等级映射（fearful/sad=2 橙色辅助，angry/disgusted=1 黄色辅助）")
        void labelToLevel(String labelEn, int expected) {
            assertThat(result(labelEn, 0.9).emotionRiskLevel()).isEqualTo(expected);
        }

        @Test
        @DisplayName("无效情绪 → 0（不参与风险判定）")
        void invalidEmotion_levelZero() {
            assertThat(result("sad", 0.3).emotionRiskLevel()).isZero();
        }
    }
}
