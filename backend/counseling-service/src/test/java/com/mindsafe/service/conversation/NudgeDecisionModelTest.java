package com.mindsafe.service.conversation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 冷场决策模型单元测试（design/28 §三 3.2 决策表回归）
 * <p>
 * 覆盖：硬规则（风险门槛/思考型问题留白/沉重倾诉宽限期）、加权评分卡（B/A/C/D/F 六信号）、
 * cap 上限机制（恐惧/倾诉/思考超时只轻陪伴）、方向映射。
 */
class NudgeDecisionModelTest {

    private final NudgeDecisionModel model = new NudgeDecisionModel();

    /** 快捷构造上下文 */
    private NudgeDecisionModel.NudgeContext ctx(String emotion, int silence, String lastType,
                                                boolean thinkingQuestion, int turn,
                                                boolean riskBlocked, long sinceLastMsg,
                                                Double expressionDepth) {
        return new NudgeDecisionModel.NudgeContext(
                emotion, silence, lastType, thinkingQuestion, turn, riskBlocked, sinceLastMsg, expressionDepth);
    }

    @Nested
    @DisplayName("硬规则（优先于评分）")
    class HardRules {

        @Test
        @DisplayName("风险≥橙色 → 留白（安全流程接管，即使长沉默+敷衍也不暖场）")
        void riskBlocked_alwaysSilence() {
            var decision = model.decide(ctx("happy", 120, NudgeDecisionModel.MSG_PERFUNCTORY,
                    false, 3, true, 120, null));
            assertThat(decision.warmthLevel()).isZero();
            assertThat(decision.direction()).isNull();
        }

        @Test
        @DisplayName("AI 刚提思考型问题 + 沉默 20s（未达思考时长 45s）→ 留白（把思考还给孩子）")
        void thinkingQuestion_withinGrace_silence() {
            var decision = model.decide(ctx("nervous", 20, NudgeDecisionModel.MSG_NORMAL,
                    true, 3, false, 20, null));
            assertThat(decision.warmthLevel()).isZero();
        }

        @Test
        @DisplayName("思考型问题 + 沉默 50s（超时）→ 轻陪伴 + 降难度（cap=1，换选择题）")
        void thinkingQuestion_overGrace_lightNudgeWithEasierQuestion() {
            var decision = model.decide(ctx("nervous", 50, NudgeDecisionModel.MSG_NORMAL,
                    true, 3, false, 50, null));
            assertThat(decision.warmthLevel()).isEqualTo(1);
            assertThat(decision.direction()).contains("降难度");
        }

        @Test
        @DisplayName("沉重倾诉宽限期内（<60s）→ 留白")
        void heavyDisclosure_withinGrace_silence() {
            var decision = model.decide(ctx("sad", 50, NudgeDecisionModel.MSG_HEAVY,
                    false, 3, false, 50, null));
            assertThat(decision.warmthLevel()).isZero();
        }

        @Test
        @DisplayName("沉重倾诉宽限期后 → 仅轻陪伴 + 稳定化方向（绝不深挖）")
        void heavyDisclosure_afterGrace_lightOnlyStabilize() {
            var decision = model.decide(ctx("sad", 70, NudgeDecisionModel.MSG_HEAVY,
                    false, 3, false, 70, null));
            assertThat(decision.warmthLevel()).isEqualTo(1);
            assertThat(decision.direction()).contains("稳定化");
        }
    }

    @Nested
    @DisplayName("决策表示例回归（design/28 §三 3.2）")
    class DecisionTableExamples {

        @Test
        @DisplayName("孩子说'没人和我玩'后沉默 40s（低落+轻微倾诉）→ 轻陪伴 + 共情（不深挖）")
        void disclosure_sad_lightCompanion() {
            var decision = model.decide(ctx("sad", 40, NudgeDecisionModel.MSG_DISCLOSURE,
                    false, 3, false, 40, null));
            assertThat(decision.warmthLevel()).isEqualTo(1);
            assertThat(decision.direction()).contains("共情陪伴");
        }

        @Test
        @DisplayName("聊体育课很开心后停顿 30s → 引导破冰 + 话题延续")
        void happy_normalTopicContinue() {
            var decision = model.decide(ctx("happy", 30, NudgeDecisionModel.MSG_NORMAL,
                    false, 3, false, 30, null));
            assertThat(decision.warmthLevel()).isEqualTo(2);
            assertThat(decision.direction()).contains("话题延续");
        }

        @Test
        @DisplayName("孩子连续'嗯''哦'后 35s（敷衍）→ 引导破冰 + 降难度（选择题）")
        void perfunctory_easierQuestion() {
            var decision = model.decide(ctx("neutral", 35, NudgeDecisionModel.MSG_PERFUNCTORY,
                    false, 3, false, 35, null));
            assertThat(decision.warmthLevel()).isEqualTo(2);
            assertThat(decision.direction()).contains("降难度");
        }

        @Test
        @DisplayName("敷衍 + 画像话多（0.7）→ F=-1 → 轻陪伴（留白让他自己组织语言）")
        void perfunctory_talkativeProfile_lightNudge() {
            var decision = model.decide(ctx("neutral", 35, NudgeDecisionModel.MSG_PERFUNCTORY,
                    false, 3, false, 35, 0.7));
            assertThat(decision.warmthLevel()).isEqualTo(1);
        }

        @Test
        @DisplayName("敷衍 + 画像沉默少言（0.3）→ F=+1 → 引导破冰 + 降难度（更主动一点）")
        void perfunctory_reservedProfile_guideNudge() {
            var decision = model.decide(ctx("neutral", 35, NudgeDecisionModel.MSG_PERFUNCTORY,
                    false, 3, false, 35, 0.3));
            assertThat(decision.warmthLevel()).isEqualTo(2);
            assertThat(decision.direction()).contains("降难度");
        }
    }

    @Nested
    @DisplayName("信号 A：情绪唤醒度")
    class EmotionSignal {

        @Test
        @DisplayName("愤怒 + 短沉默 30s → 先留白让其平复（B+1, A-1 = 0）")
        void angry_shortSilence_silence() {
            var decision = model.decide(ctx("angry", 30, NudgeDecisionModel.MSG_NORMAL,
                    false, 3, false, 30, null));
            assertThat(decision.warmthLevel()).isZero();
        }

        @Test
        @DisplayName("愤怒 + 长沉默 50s（平复后）→ 轻陪伴 + 降温/身体方向")
        void angry_longSilence_coolDown() {
            var decision = model.decide(ctx("angry", 50, NudgeDecisionModel.MSG_NORMAL,
                    false, 3, false, 50, null));
            assertThat(decision.warmthLevel()).isEqualTo(1);
            assertThat(decision.direction()).contains("降温");
        }

        @Test
        @DisplayName("恐惧 + 长沉默 50s → cap=1 只轻陪伴不提问 + 安全感方向")
        void scared_cappedAtLightCompanion() {
            var decision = model.decide(ctx("scared", 50, NudgeDecisionModel.MSG_NORMAL,
                    false, 3, false, 50, null));
            assertThat(decision.warmthLevel()).isEqualTo(1);
            assertThat(decision.direction()).contains("安全感");
        }
    }

    @Nested
    @DisplayName("信号 B：沉默时长分档")
    class SilenceDurationSignal {

        @Test
        @DisplayName("沉默 <25s（中性/普通/中期）→ 留白")
        void shortSilence_silence() {
            var decision = model.decide(ctx("neutral", 20, NudgeDecisionModel.MSG_NORMAL,
                    false, 5, false, 20, null));
            assertThat(decision.warmthLevel()).isZero();
        }

        @Test
        @DisplayName("沉默恰好 25s → B=+1（下限含）")
        void exactlyLightThreshold() {
            var decision = model.decide(ctx("neutral", 25, NudgeDecisionModel.MSG_NORMAL,
                    false, 5, false, 25, null));
            assertThat(decision.warmthLevel()).isEqualTo(1);
        }

        @Test
        @DisplayName("沉默恰好 45s → B=+1（上限不含，仍属轻陪伴档）")
        void exactlyDeepThreshold() {
            var decision = model.decide(ctx("neutral", 45, NudgeDecisionModel.MSG_NORMAL,
                    false, 5, false, 45, null));
            assertThat(decision.warmthLevel()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("信号 D：会话阶段")
    class SessionStageSignal {

        @Test
        @DisplayName("前期（≤2 轮）轻暖建立关系 → 引导破冰")
        void earlyStage_warmUp() {
            var decision = model.decide(ctx("neutral", 30, NudgeDecisionModel.MSG_NORMAL,
                    false, 1, false, 30, null));
            assertThat(decision.warmthLevel()).isEqualTo(2);
        }

        @Test
        @DisplayName("后期（≥10 轮）倾向留白/温柔收束")
        void lateStage_preferSilence() {
            var decision = model.decide(ctx("neutral", 30, NudgeDecisionModel.MSG_NORMAL,
                    false, 12, false, 30, null));
            assertThat(decision.warmthLevel()).isZero();
        }
    }

    @Nested
    @DisplayName("信号 F：画像沟通偏好")
    class ExpressionDepthSignal {

        @Test
        @DisplayName("无画像/首次对话（null）→ F=0 不参与")
        void noProfile_neutral() {
            var withNull = model.decide(ctx("neutral", 28, NudgeDecisionModel.MSG_NORMAL,
                    false, 5, false, 28, null));
            // B+1 = 1 → 轻陪伴（F 未改变结果）
            assertThat(withNull.warmthLevel()).isEqualTo(1);
        }

        @Test
        @DisplayName("话多画像（0.6 边界）→ F=-1 偏留白")
        void talkativeBoundary() {
            var decision = model.decide(ctx("neutral", 28, NudgeDecisionModel.MSG_NORMAL,
                    false, 5, false, 28, 0.6));
            // B+1, F-1 = 0 → 留白
            assertThat(decision.warmthLevel()).isZero();
        }

        @Test
        @DisplayName("沉默性格画像（0.4 边界）→ F=+1 偏暖场")
        void reservedBoundary() {
            var decision = model.decide(ctx("neutral", 28, NudgeDecisionModel.MSG_NORMAL,
                    false, 5, false, 28, 0.4));
            // B+1, F+1 = 2 → 引导破冰
            assertThat(decision.warmthLevel()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("方向映射")
    class DirectionMapping {

        @Test
        @DisplayName("中性默认 → 温柔陪伴方向")
        void defaultDirection() {
            var decision = model.decide(ctx("neutral", 28, NudgeDecisionModel.MSG_NORMAL,
                    false, 5, false, 28, null));
            assertThat(decision.direction()).contains("温柔陪伴");
        }

        @Test
        @DisplayName("留白时 direction 为 null")
        void silenceHasNullDirection() {
            var decision = model.decide(ctx("neutral", 10, NudgeDecisionModel.MSG_NORMAL,
                    false, 5, false, 10, null));
            assertThat(decision.warmthLevel()).isZero();
            assertThat(decision.direction()).isNull();
        }
    }
}
