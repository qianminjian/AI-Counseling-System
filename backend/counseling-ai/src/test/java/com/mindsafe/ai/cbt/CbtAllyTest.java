package com.mindsafe.ai.cbt;

import com.mindsafe.ai.ally.AllianceEnhancer;
import com.mindsafe.ai.cbt.CbtStageRouter.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CBT-201/202 + ALLY-201/202/203 单元测试
 */
class CbtAllyTest {

    private final CbtStageRouter router = new CbtStageRouter();
    private final AllianceEnhancer ally = new AllianceEnhancer();

    @Nested
    @DisplayName("CBT 阶段路由")
    class CbtTests {

        @Test
        @DisplayName("年龄分层：1-2=BEHAVIORAL_FIRST, 3-4=BALANCED, 5-6=COGNITIVE_FIRST")
        void ageStrategy() {
            assertThat(router.resolveAgeStrategy(1)).isEqualTo(AgeStrategy.BEHAVIORAL_FIRST);
            assertThat(router.resolveAgeStrategy(2)).isEqualTo(AgeStrategy.BEHAVIORAL_FIRST);
            assertThat(router.resolveAgeStrategy(3)).isEqualTo(AgeStrategy.BALANCED);
            assertThat(router.resolveAgeStrategy(4)).isEqualTo(AgeStrategy.BALANCED);
            assertThat(router.resolveAgeStrategy(5)).isEqualTo(AgeStrategy.COGNITIVE_FIRST);
            assertThat(router.resolveAgeStrategy(6)).isEqualTo(AgeStrategy.COGNITIVE_FIRST);
        }

        @Test
        @DisplayName("低龄行为激活：不含认知重构技术")
        void behavioralFirst() {
            StageMark mark = router.mark(CbtStage.COGNITIVE_RESTRUCTURE, 1, true);
            assertThat(mark.ageStrategy()).isEqualTo(AgeStrategy.BEHAVIORAL_FIRST);
            assertThat(mark.allowedTechniques()).doesNotContain("balanced_thought");
            assertThat(mark.allowedTechniques()).contains("emotion_labeling");
        }

        @Test
        @DisplayName("高龄认知重构：含 auto_thought_capture")
        void cognitiveFirst() {
            StageMark mark = router.mark(CbtStage.THOUGHT_RECORD, 6, true);
            assertThat(mark.ageStrategy()).isEqualTo(AgeStrategy.COGNITIVE_FIRST);
            assertThat(mark.allowedTechniques()).contains("auto_thought_capture");
        }

        @Test
        @DisplayName("allowCbt=false 时标记保留但不启用")
        void cbtGated() {
            StageMark mark = router.mark(CbtStage.THOUGHT_RECORD, 5, false);
            assertThat(mark.allowCbt()).isFalse();
        }
    }

    @Nested
    @DisplayName("ALLY 治疗联盟")
    class AllyTests {

        @Test
        @DisplayName("ALLY-201：有历史 → 生成续接提示")
        void continuity() {
            String prompt = ally.buildContinuityPrompt("和同桌的冲突", "小明");
            assertThat(prompt).contains("连续性开场");
            assertThat(prompt).contains("和同桌的冲突");
            assertThat(prompt).contains("小明");
        }

        @Test
        @DisplayName("ALLY-201：无历史 → null")
        void continuity_null() {
            assertThat(ally.buildContinuityPrompt(null, "小明")).isNull();
            assertThat(ally.buildContinuityPrompt("", "小明")).isNull();
        }

        @Test
        @DisplayName("ALLY-202：收束三段式")
        void closure() {
            String prompt = ally.buildClosurePrompt("主动分享了感受", "深呼吸");
            assertThat(prompt).contains("巩固");
            assertThat(prompt).contains("希望");
            assertThat(prompt).contains("桥接");
            assertThat(prompt).contains("主动分享了感受");
            assertThat(prompt).contains("深呼吸");
        }

        @Test
        @DisplayName("ALLY-203：7 天内无照护")
        void returnCare_none() {
            assertThat(ally.buildReturnCarePrompt(5, "小明")).isNull();
            assertThat(ally.needsReturnCare(6)).isFalse();
        }

        @Test
        @DisplayName("ALLY-203：7-13 天自然续接")
        void returnCare_week() {
            String prompt = ally.buildReturnCarePrompt(10, "小明");
            assertThat(prompt).contains("回归照护");
            assertThat(prompt).contains("10 天");
            assertThat(prompt).contains("自然续接");
        }

        @Test
        @DisplayName("ALLY-203：30+ 天温暖欢迎")
        void returnCare_month() {
            String prompt = ally.buildReturnCarePrompt(35, "小红");
            assertThat(prompt).contains("温暖欢迎");
            assertThat(prompt).contains("不追问原因");
        }
    }
}
