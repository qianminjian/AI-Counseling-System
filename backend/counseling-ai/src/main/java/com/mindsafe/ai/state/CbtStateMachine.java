package com.mindsafe.ai.state;

import com.mindsafe.common.enums.RiskLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * CBT 状态机引擎（对齐 design/13 §10.1）
 * <p>
 * 基于显式状态转移表驱动 CBT 干预流程。
 * 全局风险覆盖：任意状态出现 R3+（ORANGE/RED）→ 强制转 S9_ESCALATE。
 * <p>
 * 状态转移表：
 * S0_START → S1_SAFETY_PRECHECK (user_engaged)
 * S1_SAFETY_PRECHECK → S2_EMOTION_LABEL (risk_safe) | S9_ESCALATE (risk_high)
 * S2_EMOTION_LABEL → S3_SCENARIO_ROUTE (emotion_obtained)
 * S3_SCENARIO_ROUTE → S4_EVENT_FACT (scenario_matched)
 * S4_EVENT_FACT → S5_AUTO_THOUGHT (event_confirmed)
 * S5_AUTO_THOUGHT → S6_REFRAME (thought_identified)
 * S6_REFRAME → S7_MICRO_ACTION (balanced_thought)
 * S7_MICRO_ACTION → S8_RECHECK_CLOSE (action_selected)
 * S8_RECHECK_CLOSE → END (risk_stable) | S9_ESCALATE (risk_escalated)
 * S9_ESCALATE → END (notification_sent)
 */
@Component
public class CbtStateMachine {

    private static final Logger log = LoggerFactory.getLogger(CbtStateMachine.class);

    /** 状态转移表 */
    private static final Map<CbtState, List<Transition>> TRANSITIONS = Map.ofEntries(
            Map.entry(CbtState.S0_START, List.of(
                    new Transition("user_engaged", CbtState.S1_SAFETY_PRECHECK)
            )),
            Map.entry(CbtState.S1_SAFETY_PRECHECK, List.of(
                    new Transition("risk_safe", CbtState.S2_EMOTION_LABEL),
                    new Transition("risk_high", CbtState.S9_ESCALATE)
            )),
            Map.entry(CbtState.S2_EMOTION_LABEL, List.of(
                    new Transition("emotion_obtained", CbtState.S3_SCENARIO_ROUTE)
            )),
            Map.entry(CbtState.S3_SCENARIO_ROUTE, List.of(
                    new Transition("scenario_matched", CbtState.S4_EVENT_FACT)
            )),
            Map.entry(CbtState.S4_EVENT_FACT, List.of(
                    new Transition("event_confirmed", CbtState.S5_AUTO_THOUGHT)
            )),
            Map.entry(CbtState.S5_AUTO_THOUGHT, List.of(
                    new Transition("thought_identified", CbtState.S6_REFRAME)
            )),
            Map.entry(CbtState.S6_REFRAME, List.of(
                    new Transition("balanced_thought", CbtState.S7_MICRO_ACTION)
            )),
            Map.entry(CbtState.S7_MICRO_ACTION, List.of(
                    new Transition("action_selected", CbtState.S8_RECHECK_CLOSE)
            )),
            Map.entry(CbtState.S8_RECHECK_CLOSE, List.of(
                    new Transition("risk_stable", CbtState.END),
                    new Transition("risk_escalated", CbtState.S9_ESCALATE)
            )),
            Map.entry(CbtState.S9_ESCALATE, List.of(
                    new Transition("notification_sent", CbtState.END)
            ))
    );

    /**
     * 评估状态转移
     *
     * @param current   当前状态
     * @param trigger   触发事件
     * @param riskLevel 当前风险等级（用于全局覆盖判断）
     * @return 转移结果
     */
    public TransitionResult evaluate(CbtState current, String trigger, RiskLevel riskLevel) {
        // 全局风险覆盖：任意状态出现 ORANGE/RED → 强制转 S9_ESCALATE
        if (riskLevel != null && riskLevel.isHighRisk() && current != CbtState.S9_ESCALATE) {
            log.warn("全局风险覆盖: {} → S9_ESCALATE (riskLevel={})", current, riskLevel);
            return TransitionResult.force(CbtState.S9_ESCALATE, "global_risk_override");
        }

        // 终态不再转移
        if (current.isTerminal()) {
            return TransitionResult.stay(current, "terminal_state");
        }

        // 正常转移：查找匹配的 trigger
        List<Transition> transitions = TRANSITIONS.getOrDefault(current, List.of());
        return transitions.stream()
                .filter(t -> t.trigger().equals(trigger))
                .findFirst()
                .map(t -> {
                    log.debug("状态转移: {} → {} (trigger={})", current, t.target(), trigger);
                    return TransitionResult.of(t.target(), trigger);
                })
                .orElseGet(() -> {
                    log.debug("无匹配转移，保持当前状态: {} (trigger={})", current, trigger);
                    return TransitionResult.stay(current, "no_matching_transition");
                });
    }

    /**
     * 简化版评估：基于 Orchestrator 编排结果自动推断 trigger
     * <p>
     * 用于 ConversationOrchestrator 调用，根据当前状态和编排结果推断下一步。
     */
    public TransitionResult evaluateFromOrchestration(CbtState current, boolean emotionObtained,
                                                      boolean eventConfirmed, boolean thoughtIdentified,
                                                      boolean balancedThought, boolean actionSelected,
                                                      RiskLevel riskLevel) {
        // 全局风险覆盖优先
        if (riskLevel != null && riskLevel.isHighRisk() && current != CbtState.S9_ESCALATE) {
            return TransitionResult.force(CbtState.S9_ESCALATE, "global_risk_override");
        }

        String trigger = inferTrigger(current, emotionObtained, eventConfirmed,
                thoughtIdentified, balancedThought, actionSelected);
        return evaluate(current, trigger, null); // 风险已在上面处理
    }

    /**
     * 根据当前状态和条件推断 trigger
     */
    private String inferTrigger(CbtState current, boolean emotionObtained, boolean eventConfirmed,
                                boolean thoughtIdentified, boolean balancedThought, boolean actionSelected) {
        return switch (current) {
            case S0_START -> "user_engaged";
            case S1_SAFETY_PRECHECK -> "risk_safe";
            case S2_EMOTION_LABEL -> emotionObtained ? "emotion_obtained" : "no_matching_transition";
            case S3_SCENARIO_ROUTE -> "scenario_matched";
            case S4_EVENT_FACT -> eventConfirmed ? "event_confirmed" : "no_matching_transition";
            case S5_AUTO_THOUGHT -> thoughtIdentified ? "thought_identified" : "no_matching_transition";
            case S6_REFRAME -> balancedThought ? "balanced_thought" : "no_matching_transition";
            case S7_MICRO_ACTION -> actionSelected ? "action_selected" : "no_matching_transition";
            case S8_RECHECK_CLOSE -> "risk_stable";
            case S9_ESCALATE -> "notification_sent";
            case END -> "no_matching_transition";
        };
    }

    // ===== 内部类型 =====

    /** 状态转移定义 */
    public record Transition(String trigger, CbtState target) {}

    /** 状态转移结果 */
    public record TransitionResult(
            CbtState fromState,
            CbtState toState,
            String trigger,
            boolean forced,
            boolean transitioned
    ) {
        public static TransitionResult of(CbtState target, String trigger) {
            return new TransitionResult(null, target, trigger, false, true);
        }

        public static TransitionResult force(CbtState target, String reason) {
            return new TransitionResult(null, target, reason, true, true);
        }

        public static TransitionResult stay(CbtState current, String reason) {
            return new TransitionResult(current, current, reason, false, false);
        }
    }
}
