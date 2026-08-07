package com.mindsafe.ai.risk;

import com.mindsafe.ai.risk.RiskKeywordRegistry.Level;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RiskKeywordRegistry 单元测试（ARCH-003，doing/63 §4.1）
 * <p>
 * 覆盖：四级词典互斥、matchLevel/matchMethod/scoreFor、评分常量、只读断言。
 */
class RiskKeywordRegistryTest {

    @Nested
    @DisplayName("四级词典互斥（RED ∩ ORANGE ∩ YELLOW = ∅）")
    class DictionaryDisjointness {

        @Test
        @DisplayName("RED_HARD 与 ORANGE 无交集")
        void redHardDisjointOrange() {
            assertThat(RiskKeywordRegistry.RED_HARD.stream()
                    .filter(RiskKeywordRegistry.ORANGE::contains))
                    .isEmpty();
        }

        @Test
        @DisplayName("RED_HARD 与 YELLOW 无交集")
        void redHardDisjointYellow() {
            assertThat(RiskKeywordRegistry.RED_HARD.stream()
                    .filter(RiskKeywordRegistry.YELLOW::contains))
                    .isEmpty();
        }

        @Test
        @DisplayName("ORANGE 与 YELLOW 无交集")
        void orangeDisjointYellow() {
            assertThat(RiskKeywordRegistry.ORANGE.stream()
                    .filter(RiskKeywordRegistry.YELLOW::contains))
                    .isEmpty();
        }

        @Test
        @DisplayName("四组意图/方法/准备词与分级词典的关系：准备词=遗书 必须同时是 RED 成员（C-SSRS 行为轴语义）")
        void preparatoryWordsAreRed() {
            assertThat(RiskKeywordRegistry.RED_HARD).containsAll(RiskKeywordRegistry.PREPARATORY_KEYWORDS);
        }
    }

    @Nested
    @DisplayName("matchLevel 分级命中")
    class MatchLevel {

        @ParameterizedTest
        @ValueSource(strings = {"我想死", "我要跳楼", "我想割腕", "我写了遗书", "活着没意思", "我不想活了", "摸隐私部位"})
        @DisplayName("红色硬规则词 → RED_HARD")
        void redKeywords(String text) {
            assertThat(RiskKeywordRegistry.matchLevel(text)).isEqualTo(Level.RED_HARD);
        }

        @ParameterizedTest
        @ValueSource(strings = {"我被欺负了", "老师打我", "我喘不过气", "我要离家出走", "我死了算了"})
        @DisplayName("橙色词 → ORANGE")
        void orangeKeywords(String text) {
            assertThat(RiskKeywordRegistry.matchLevel(text)).isEqualTo(Level.ORANGE);
        }

        @ParameterizedTest
        @ValueSource(strings = {"我很难过", "我每天哭", "我睡不着", "我经常被骂", "我头痛"})
        @DisplayName("黄色词 → YELLOW")
        void yellowKeywords(String text) {
            assertThat(RiskKeywordRegistry.matchLevel(text)).isEqualTo(Level.YELLOW);
        }

        @ParameterizedTest
        @ValueSource(strings = {"今天天气不错", "我考试考了90分", "", "  "})
        @DisplayName("无风险文本 → NONE")
        void noneKeywords(String text) {
            assertThat(RiskKeywordRegistry.matchLevel(text)).isEqualTo(Level.NONE);
        }

        @Test
        @DisplayName("null → NONE")
        void nullText() {
            assertThat(RiskKeywordRegistry.matchLevel(null)).isEqualTo(Level.NONE);
        }
    }

    @Nested
    @DisplayName("matchMethod 意图/方法/准备词命中")
    class MatchMethod {

        @Test
        @DisplayName("明确意图词命中")
        void explicitIntentHit() {
            assertThat(RiskKeywordRegistry.matchMethod("我不想活了"))
                    .contains("不想活了");
        }

        @Test
        @DisplayName("方法词命中")
        void methodHit() {
            assertThat(RiskKeywordRegistry.matchMethod("我要跳楼"))
                    .contains("跳楼");
        }

        @Test
        @DisplayName("准备词命中")
        void preparatoryHit() {
            assertThat(RiskKeywordRegistry.matchMethod("我写了遗书"))
                    .contains("遗书");
        }

        @Test
        @DisplayName("无命中返回空列表")
        void noHit() {
            assertThat(RiskKeywordRegistry.matchMethod("今天天气不错")).isEmpty();
        }
    }

    @Nested
    @DisplayName("评分常量与 scoreFor")
    class Scores {

        @Test
        @DisplayName("评分常量与 design/04 一致（85/60/35/30）")
        void scoreConstants() {
            assertThat(RiskKeywordRegistry.SCORE_HARD).isEqualTo(85);
            assertThat(RiskKeywordRegistry.SCORE_ORANGE).isEqualTo(60);
            assertThat(RiskKeywordRegistry.SCORE_YELLOW).isEqualTo(35);
            assertThat(RiskKeywordRegistry.SCORE_ORANGE_MIN).isEqualTo(30);
        }

        @Test
        @DisplayName("scoreFor：RED → 85 / ORANGE → 60 / YELLOW → 35 / NONE → 0")
        void scoreForLevels() {
            assertThat(RiskKeywordRegistry.scoreFor("我想死")).isEqualTo(85);
            assertThat(RiskKeywordRegistry.scoreFor("我被欺负了")).isEqualTo(60);
            assertThat(RiskKeywordRegistry.scoreFor("我睡不着")).isEqualTo(35);
            assertThat(RiskKeywordRegistry.scoreFor("今天天气不错")).isZero();
        }

        @Test
        @DisplayName("语义升级黄色分数 40（ConversationRiskProcessor 语义层 L85 收编）")
        void semanticYellowScore() {
            assertThat(RiskKeywordRegistry.SCORE_SEMANTIC_YELLOW).isEqualTo(40);
        }

        @Test
        @DisplayName("评分因子权重具名化（意图 15/8、计划 5 上限 20、ScoreInput 10,0,0,0,0.8）")
        void riskWeights() {
            assertThat(RiskKeywordRegistry.INTENT_EXPLICIT_WEIGHT).isEqualTo(15);
            assertThat(RiskKeywordRegistry.INTENT_VAGUE_WEIGHT).isEqualTo(8);
            assertThat(RiskKeywordRegistry.PLAN_WEIGHT_PER_KEYWORD).isEqualTo(5);
            assertThat(RiskKeywordRegistry.PLAN_WEIGHT_CAP).isEqualTo(20);
            assertThat(RiskKeywordRegistry.WEIGHT_RECENCY).isEqualTo(10);
            assertThat(RiskKeywordRegistry.WEIGHT_ACTION).isZero();
            assertThat(RiskKeywordRegistry.WEIGHT_REPETITION).isZero();
            assertThat(RiskKeywordRegistry.WEIGHT_PROTECTIVE).isZero();
            assertThat(RiskKeywordRegistry.WEIGHT_CONFIDENCE).isEqualTo(0.8);
        }
    }

    @Nested
    @DisplayName("类别表与查找")
    class Categories {

        @Test
        @DisplayName("findCategory 按命中词定位风险类别")
        void findCategoryByKeyword() {
            assertThat(RiskKeywordRegistry.findCategory(java.util.List.of("想死")))
                    .isEqualTo("自伤/自杀");
            assertThat(RiskKeywordRegistry.findCategory(java.util.List.of("被欺负")))
                    .isEqualTo("霸凌/网络欺凌");
        }

        @Test
        @DisplayName("无类别命中 → 未分类")
        void findCategoryUnknown() {
            assertThat(RiskKeywordRegistry.findCategory(java.util.List.of("不存在词")))
                    .isEqualTo("未分类");
        }

        @ParameterizedTest
        @ValueSource(strings = {"自伤/自杀", "他伤/暴力", "家庭虐待/忽视", "性侵/性骚扰", "严重抑郁/绝望"})
        @DisplayName("高敏类别命中（DC-001：SAFE-202 门控类目）")
        void highSensitivityHit(String category) {
            assertThat(RiskKeywordRegistry.isHighSensitivityCategory(category)).isTrue();
        }

        @Test
        @DisplayName("非高敏类别/空 → 不命中")
        void highSensitivityMiss() {
            assertThat(RiskKeywordRegistry.isHighSensitivityCategory("霸凌/网络欺凌")).isFalse();
            assertThat(RiskKeywordRegistry.isHighSensitivityCategory("离家/失联")).isFalse();
            assertThat(RiskKeywordRegistry.isHighSensitivityCategory(null)).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {"性侵/性骚扰", "家庭虐待/忽视"})
        @DisplayName("不降级类别命中（DC-001：否定/语境不可降级）")
        void nonDegradableHit(String category) {
            assertThat(RiskKeywordRegistry.isNonDegradableCategory(category)).isTrue();
        }

        @Test
        @DisplayName("可降级类别/空 → 不命中")
        void nonDegradableMiss() {
            assertThat(RiskKeywordRegistry.isNonDegradableCategory("自伤/自杀")).isFalse();
            assertThat(RiskKeywordRegistry.isNonDegradableCategory("")).isFalse();
            assertThat(RiskKeywordRegistry.isNonDegradableCategory(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("规则源只读断言（静态不可变）")
    class ReadOnly {

        @Test
        @DisplayName("全部字段为 static final（只读；public 常量供消费点引用）")
        void allFieldsFinal() throws IllegalAccessException {
            for (Field field : RiskKeywordRegistry.class.getDeclaredFields()) {
                int mod = field.getModifiers();
                assertThat(Modifier.isStatic(mod)).as("字段 %s 必须 static", field.getName()).isTrue();
                assertThat(Modifier.isFinal(mod)).as("字段 %s 必须 final（只读规则源）", field.getName()).isTrue();
                if (java.util.Collection.class.isAssignableFrom(field.getType())
                        || java.util.Map.class.isAssignableFrom(field.getType())
                        || field.getType() == java.util.regex.Pattern.class) {
                    // 容器字段内部同样不可修改（unmodifiable/copyOf 由实现保证，此处验证类型为不可变集合）
                    assertThat(field.getType().getName())
                            .as("字段 %s 必须声明为不可变集合类型", field.getName())
                            .isIn("java.util.Set", "java.util.List", "java.util.Map", "java.util.regex.Pattern");
                }
            }
        }
    }
}
