package com.mindsafe.ai.risk;

import com.mindsafe.ai.risk.EmotionVocabulary.Category;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EmotionVocabulary 单元测试（ARCH-003，doing/63 §4.2）
 * <p>
 * 覆盖：三分类完备、中英别名、anxious 全管线一致、只读断言。
 */
class EmotionVocabularyTest {

    @Nested
    @DisplayName("三分类完备（NEGATIVE ∩ POSITIVE = ∅；UNKNOWN 兜底）")
    class Classification {

        @Test
        @DisplayName("负面/正面集合互斥")
        void negativeDisjointPositive() {
            assertThat(EmotionVocabulary.NEGATIVE_KEYS.stream()
                    .filter(EmotionVocabulary.POSITIVE_KEYS::contains))
                    .isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"sad", "fearful", "angry", "anxious", "disgusted", "withdrawn", "crisis", "lonely"})
        @DisplayName("负面权威成员 → NEGATIVE")
        void negativeKeys(String key) {
            assertThat(EmotionVocabulary.classify(key)).isEqualTo(Category.NEGATIVE);
        }

        @ParameterizedTest
        @ValueSource(strings = {"happy", "calm", "relieved", "hopeful", "neutral"})
        @DisplayName("正面权威成员 → POSITIVE")
        void positiveKeys(String key) {
            assertThat(EmotionVocabulary.classify(key)).isEqualTo(Category.POSITIVE);
        }

        @ParameterizedTest
        @ValueSource(strings = {"surprised", "unknown", "", "  "})
        @DisplayName("未收录/空 → UNKNOWN")
        void unknownKeys(String key) {
            assertThat(EmotionVocabulary.classify(key)).isEqualTo(Category.UNKNOWN);
        }

        @Test
        @DisplayName("null → UNKNOWN")
        void nullKey() {
            assertThat(EmotionVocabulary.classify(null)).isEqualTo(Category.UNKNOWN);
        }
    }

    @Nested
    @DisplayName("中文别名与子串匹配（LongTermMemoryService 语义收编）")
    class ChineseAlias {

        @ParameterizedTest
        @ValueSource(strings = {"难过", "悲伤", "生气", "愤怒", "害怕", "恐惧", "焦虑", "厌恶", "退缩", "孤独", "危机"})
        @DisplayName("中文负面别名 → NEGATIVE")
        void negativeChinese(String key) {
            assertThat(EmotionVocabulary.classify(key)).isEqualTo(Category.NEGATIVE);
        }

        @ParameterizedTest
        @ValueSource(strings = {"开心", "高兴", "平静", "放松", "希望"})
        @DisplayName("中文正面别名 → POSITIVE")
        void positiveChinese(String key) {
            assertThat(EmotionVocabulary.classify(key)).isEqualTo(Category.POSITIVE);
        }

        @ParameterizedTest
        @ValueSource(strings = {"sad + lonely", "feels fearful today", "有点悲伤", "孩子有点焦虑"})
        @DisplayName("组合文本含负面子串 → NEGATIVE（记忆回注场景）")
        void substringNegative(String text) {
            assertThat(EmotionVocabulary.classify(text)).isEqualTo(Category.NEGATIVE);
        }

        @ParameterizedTest
        @ValueSource(strings = {"feels sad today", "angry mood", "sadness", "anxiously"})
        @DisplayName("LongTermMemoryService 原 contains 语义收编：sad/angry/anxious 子串 → NEGATIVE")
        void substringNegativeEnglishOriginal(String text) {
            // 消费点 6 原逻辑为 contains("sad")||contains("angry")||contains("anxious") 等子串匹配，
            // 收编后必须保持该语义（行为零变更锚点，2026-08-06 调研核对补充）
            assertThat(EmotionVocabulary.classify(text)).isEqualTo(Category.NEGATIVE);
        }

        @ParameterizedTest
        @ValueSource(strings = {"feels happy today", "今天很开心"})
        @DisplayName("组合文本含正面子串 → POSITIVE")
        void substringPositive(String text) {
            assertThat(EmotionVocabulary.classify(text)).isEqualTo(Category.POSITIVE);
        }
    }

    @Nested
    @DisplayName("anxious 全管线一致（doing/63 核心隐患修复）")
    class AnxiousConsistency {

        @Test
        @DisplayName("anxious 必须为权威负面成员（会话风险/冷场计数/结束分析/记忆回注一致）")
        void anxiousIsNegative() {
            assertThat(EmotionVocabulary.isNegative("anxious")).isTrue();
            assertThat(EmotionVocabulary.classify("anxious")).isEqualTo(Category.NEGATIVE);
        }

        @ParameterizedTest
        @ValueSource(strings = {"sad", "fearful", "angry", "disgusted", "anxious", "withdrawn", "crisis"})
        @DisplayName("isNegative 与 classify 一致")
        void isNegativeMatchesClassify(String key) {
            assertThat(EmotionVocabulary.isNegative(key))
                    .isEqualTo(EmotionVocabulary.classify(key) == Category.NEGATIVE);
        }
    }

    @Nested
    @DisplayName("规则源只读断言（静态不可变）")
    class ReadOnly {

        @Test
        @DisplayName("全部字段为 static final（只读；public 常量供消费点引用）")
        void allFieldsFinal() {
            for (Field field : EmotionVocabulary.class.getDeclaredFields()) {
                int mod = field.getModifiers();
                assertThat(Modifier.isStatic(mod)).as("字段 %s 必须 static", field.getName()).isTrue();
                assertThat(Modifier.isFinal(mod)).as("字段 %s 必须 final（只读规则源）", field.getName()).isTrue();
            }
        }
    }
}
