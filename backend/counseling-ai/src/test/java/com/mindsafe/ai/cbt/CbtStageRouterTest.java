package com.mindsafe.ai.cbt;

import com.mindsafe.ai.orchestrator.StrategyProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CbtStageRouter 单测（CBT-201/202，design/03 §11.3/11.4）。
 * <p>
 * 覆盖：年龄分层路由 / 阶段推断（容纳之窗门控）/ BALANCED 技术矩阵全阶段 / 阶段指令渲染。
 */
@DisplayName("CBT 阶段路由器")
class CbtStageRouterTest {

    private final CbtStageRouter router = new CbtStageRouter();

    @Nested
    @DisplayName("resolveAgeStrategy 年龄分层（design/29 三档）")
    class AgeStrategyRouting {

        @ParameterizedTest
        @CsvSource({
                "1, BEHAVIORAL_FIRST", "2, BEHAVIORAL_FIRST",
                "3, BALANCED", "4, BALANCED",
                "5, COGNITIVE_FIRST", "6, COGNITIVE_FIRST"
        })
        @DisplayName("年级 → 分层策略")
        void gradeToStrategy(int grade, CbtStageRouter.AgeStrategy expected) {
            assertThat(router.resolveAgeStrategy(grade)).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("inferStage 阶段推断（容纳之窗门控）")
    class InferStage {

        @Test
        @DisplayName("CRISIS/ACTIVATED 高唤醒 → SKILL_PRACTICE（稳定化，不论轮次）")
        void highArousal_goesSkillPractice() {
            assertThat(router.inferStage(1, StrategyProfile.EmotionState.CRISIS))
                    .isEqualTo(CbtStageRouter.CbtStage.SKILL_PRACTICE);
            assertThat(router.inferStage(10, StrategyProfile.EmotionState.ACTIVATED))
                    .isEqualTo(CbtStageRouter.CbtStage.SKILL_PRACTICE);
        }

        @ParameterizedTest
        @CsvSource({"1, RAPPORT", "2, RAPPORT", "3, PROBLEM_IDENTIFY", "8, PROBLEM_IDENTIFY", "9, CLOSURE", "12, CLOSURE"})
        @DisplayName("STABLE 态按轮次代理：≤2 建关系 / 3-8 问题识别 / ≥9 收束")
        void stableByTurn(int turn, CbtStageRouter.CbtStage expected) {
            assertThat(router.inferStage(turn, StrategyProfile.EmotionState.STABLE)).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("mark 技术矩阵")
    class MarkMatrix {

        @Test
        @DisplayName("BALANCED（3-4 年级）全阶段技术列表")
        void balancedMatrix_allStages() {
            assertThat(techniques(CbtStageRouter.CbtStage.RAPPORT, 3))
                    .containsExactly("rapport_talk", "emotion_check");
            assertThat(techniques(CbtStageRouter.CbtStage.PROBLEM_IDENTIFY, 4))
                    .containsExactly("emotion_thermometer", "situation_analysis");
            assertThat(techniques(CbtStageRouter.CbtStage.THOUGHT_RECORD, 3))
                    .containsExactly("simple_thought_capture");
            assertThat(techniques(CbtStageRouter.CbtStage.BEHAVIORAL_ACTIVATION, 4))
                    .containsExactly("micro_action", "activity_schedule");
            assertThat(techniques(CbtStageRouter.CbtStage.SKILL_PRACTICE, 3))
                    .containsExactly("breathing", "positive_self_talk");
            assertThat(techniques(CbtStageRouter.CbtStage.CLOSURE, 4))
                    .containsExactly("recap", "hope_anchor");
            // 未显式定义的中龄阶段走默认（具象引导）
            assertThat(techniques(CbtStageRouter.CbtStage.COGNITIVE_RESTRUCTURE, 3))
                    .containsExactly("emotion_labeling", "guided_discovery");
        }

        @Test
        @DisplayName("BEHAVIORAL_FIRST（1-2 年级）：无认知重构技术")
        void behavioralFirst_noCognitiveTechniques() {
            List<String> techniques = techniques(CbtStageRouter.CbtStage.PROBLEM_IDENTIFY, 1);
            assertThat(techniques).containsExactly("emotion_thermometer", "story_metaphor");
            assertThat(techniques(CbtStageRouter.CbtStage.THOUGHT_RECORD, 2))
                    .containsExactly("emotion_labeling", "behavioral_prompt");
        }

        @Test
        @DisplayName("COGNITIVE_FIRST（5-6 年级）：认知重构优先")
        void cognitiveFirst_restructureTechniques() {
            assertThat(techniques(CbtStageRouter.CbtStage.COGNITIVE_RESTRUCTURE, 5))
                    .containsExactly("balanced_thought", "perspective_shift");
            assertThat(techniques(CbtStageRouter.CbtStage.RELAPSE_PREVENTION, 6))
                    .containsExactly("coping_card", "warning_signs");
        }

        @Test
        @DisplayName("allowCbt 门控值透传到 StageMark")
        void allowCbtPassthrough() {
            assertThat(router.mark(CbtStageRouter.CbtStage.RAPPORT, 3, true).allowCbt()).isTrue();
            assertThat(router.mark(CbtStageRouter.CbtStage.RAPPORT, 3, false).allowCbt()).isFalse();
        }

        private List<String> techniques(CbtStageRouter.CbtStage stage, int grade) {
            return router.mark(stage, grade, true).allowedTechniques();
        }
    }

    @Nested
    @DisplayName("stageDirective 阶段指令渲染（WIRE-002）")
    class StageDirective {

        @Test
        @DisplayName("allowCbt=false → 稳定化指令（只呼吸/着陆/命名，不做认知重构）")
        void notAllowed_stabilizationDirective() {
            CbtStageRouter.StageMark mark = router.mark(CbtStageRouter.CbtStage.SKILL_PRACTICE, 5, false);

            String directive = router.stageDirective(mark);

            assertThat(directive)
                    .contains("稳定化陪伴")
                    .contains("不做认知重构");
        }

        @Test
        @DisplayName("BALANCED 允许 → 含阶段名、允许技术、具象化工具年龄约束")
        void balanced_directiveContainsConstraints() {
            CbtStageRouter.StageMark mark = router.mark(CbtStageRouter.CbtStage.PROBLEM_IDENTIFY, 3, true);

            String directive = router.stageDirective(mark);

            assertThat(directive)
                    .contains("PROBLEM_IDENTIFY")
                    .contains("emotion_thermometer")
                    .contains("想法天平");
        }

        @Test
        @DisplayName("BEHAVIORAL_FIRST 允许 → 低年级不做认知重构约束")
        void behavioralFirst_directiveConstraint() {
            CbtStageRouter.StageMark mark = router.mark(CbtStageRouter.CbtStage.RAPPORT, 1, true);

            assertThat(router.stageDirective(mark)).contains("低年级不做认知重构");
        }

        @Test
        @DisplayName("COGNITIVE_FIRST 允许 → 无年龄约束附加文案")
        void cognitiveFirst_noAgeConstraintText() {
            CbtStageRouter.StageMark mark = router.mark(CbtStageRouter.CbtStage.THOUGHT_RECORD, 6, true);

            String directive = router.stageDirective(mark);

            assertThat(directive)
                    .contains("THOUGHT_RECORD")
                    .doesNotContain("年龄约束");
        }
    }
}
