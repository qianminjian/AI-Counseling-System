package com.mindsafe.service.conversation.strategy;

import com.mindsafe.ai.orchestrator.StrategyProfile.EmotionState;
import com.mindsafe.service.conversation.NudgeDecisionModel.NudgeDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NudgeStrategy 单元测试（DC-010，doing/72 §24）
 * <p>
 * 覆盖：情绪旅程约束（非 STABLE 高 warmth 降级）/ 连续积极回应方向调整 / STABLE 不干预 /
 * 参数不可变（原 decision 对象不变）。
 */
class NudgeStrategyTest {

    @Nested
    @DisplayName("情绪旅程约束（非 STABLE → 强制轻陪伴）")
    class EmotionJourneyConstraint {

        @Test
        @DisplayName("ACTIVATED + warmth=2 → 降级为 1，direction 不变")
        void activatedHighWarmthDowngraded() {
            NudgeDecision d = new NudgeDecision(2, "引导破冰");

            NudgeDecision adjusted = NudgeStrategy.adjust(d, EmotionState.ACTIVATED, 0);

            assertThat(adjusted.warmthLevel()).isEqualTo(1);
            assertThat(adjusted.direction()).isEqualTo("引导破冰");
        }

        @Test
        @DisplayName("CRISIS + warmth=2 → 降级为 1（危机状态不引导破冰）")
        void crisisHighWarmthDowngraded() {
            NudgeDecision adjusted = NudgeStrategy.adjust(new NudgeDecision(2, "引导破冰"), EmotionState.CRISIS, 0);

            assertThat(adjusted.warmthLevel()).isEqualTo(1);
        }

        @Test
        @DisplayName("非 STABLE 但 warmth=1 → 不干预（已是最轻强度）")
        void nonStableWarmthOneUntouched() {
            NudgeDecision d = new NudgeDecision(1, "轻陪伴");

            NudgeDecision adjusted = NudgeStrategy.adjust(d, EmotionState.ACTIVATED, 0);

            assertThat(adjusted.warmthLevel()).isEqualTo(1);
        }

        @Test
        @DisplayName("STABLE + warmth=2 → 不干预（可正常引导破冰）")
        void stableUntouched() {
            NudgeDecision d = new NudgeDecision(2, "引导破冰");

            NudgeDecision adjusted = NudgeStrategy.adjust(d, EmotionState.STABLE, 0);

            assertThat(adjusted.warmthLevel()).isEqualTo(2);
            assertThat(adjusted.direction()).isEqualTo("引导破冰");
        }
    }

    @Nested
    @DisplayName("连续积极回应方向调整（reliefCount >= 3）")
    class ReliefDirection {

        @Test
        @DisplayName("reliefCount>=3 + warmth>0 → direction 调整为积极肯定，warmth 不变")
        void reliefCountDirectsPositive() {
            NudgeDecision d = new NudgeDecision(1, "轻陪伴");

            NudgeDecision adjusted = NudgeStrategy.adjust(d, EmotionState.STABLE, 3);

            assertThat(adjusted.direction()).isEqualTo("积极肯定");
            assertThat(adjusted.warmthLevel()).isEqualTo(1);
        }

        @Test
        @DisplayName("reliefCount>=3 但 warmth=0 → 不干预（留白保持，不强行说话）")
        void reliefCountZeroWarmthUntouched() {
            NudgeDecision d = new NudgeDecision(0, null);

            NudgeDecision adjusted = NudgeStrategy.adjust(d, EmotionState.STABLE, 5);

            assertThat(adjusted).isEqualTo(d);
        }

        @Test
        @DisplayName("reliefCount<3 → 不调整方向")
        void lowReliefUntouched() {
            NudgeDecision d = new NudgeDecision(2, "引导破冰");

            NudgeDecision adjusted = NudgeStrategy.adjust(d, EmotionState.STABLE, 2);

            assertThat(adjusted.direction()).isEqualTo("引导破冰");
        }
    }

    @Nested
    @DisplayName("组合与不可变性")
    class CombinedAndImmutability {

        @Test
        @DisplayName("两条规则同时命中：warmth 降级为 1 且 direction 积极肯定")
        void bothRulesApply() {
            NudgeDecision d = new NudgeDecision(2, "引导破冰");

            NudgeDecision adjusted = NudgeStrategy.adjust(d, EmotionState.ACTIVATED, 4);

            assertThat(adjusted.warmthLevel()).isEqualTo(1);
            assertThat(adjusted.direction()).isEqualTo("积极肯定");
        }

        @Test
        @DisplayName("参数不可变：原 decision 对象不被修改（返回新实例）")
        void originalUnchanged() {
            NudgeDecision d = new NudgeDecision(2, "引导破冰");

            NudgeStrategy.adjust(d, EmotionState.ACTIVATED, 4);

            assertThat(d.warmthLevel()).isEqualTo(2);
            assertThat(d.direction()).isEqualTo("引导破冰");
        }

        @Test
        @DisplayName("null 入参 → 返回 null（调用方防御）")
        void nullInputReturnsNull() {
            assertThat(NudgeStrategy.adjust(null, EmotionState.STABLE, 0)).isNull();
        }
    }
}
