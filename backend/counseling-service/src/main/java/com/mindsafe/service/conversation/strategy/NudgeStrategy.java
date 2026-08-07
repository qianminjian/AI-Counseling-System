package com.mindsafe.service.conversation.strategy;

import com.mindsafe.ai.orchestrator.StrategyProfile.EmotionState;
import com.mindsafe.service.conversation.NudgeDecisionModel.NudgeDecision;

import java.util.Objects;

/**
 * 暖场（nudge）策略（DC-010，doing/72 §24）
 * <p>
 * 从 ConversationServiceImpl 编排层下沉的纯静态决策：
 * <ul>
 *   <li>情绪旅程约束：非 STABLE 且 warmth>1 → 强制轻陪伴（warmth=1，不引导破冰）</li>
 *   <li>连续积极回应 >= 3 且 warmth>0 → 方向偏积极肯定</li>
 * </ul>
 * NudgeDecision 不可变 record，返回新实例，原对象不被修改。
 */
public final class NudgeStrategy {

    private NudgeStrategy() {
    }

    /**
     * 调整暖场决策（两条规则可叠加）。
     *
     * @param d          原决策（不可变，不会被修改）
     * @param state      情绪旅程状态（STABLE / ACTIVATED / CRISIS）
     * @param reliefCount 连续积极回应次数
     * @return 调整后的决策（无调整时返回原实例）
     */
    public static NudgeDecision adjust(NudgeDecision d, EmotionState state, int reliefCount) {
        if (d == null) {
            return null;
        }
        int warmth = d.warmthLevel();
        String direction = d.direction();

        // 情绪旅程约束：ACTIVATED/CRISIS 时强制轻陪伴，不引导破冰
        if (state != EmotionState.STABLE && warmth > 1) {
            warmth = 1;
        }
        // 连续积极回应 >= 3 时，暖场方向偏向积极肯定
        if (reliefCount >= 3 && warmth > 0) {
            direction = "积极肯定";
        }

        if (warmth == d.warmthLevel() && Objects.equals(direction, d.direction())) {
            return d;
        }
        return new NudgeDecision(warmth, direction);
    }
}
