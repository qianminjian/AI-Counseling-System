package com.mindsafe.ai.state;

import com.mindsafe.common.enums.RiskLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CBT 状态机单元测试
 * 覆盖：正常转移链路 / 全局风险覆盖 / 终态不转移 / 无匹配 trigger 保持
 */
class CbtStateMachineTest {

    private CbtStateMachine sm;

    @BeforeEach
    void setUp() {
        sm = new CbtStateMachine();
    }

    @Test
    @DisplayName("完整 CBT 流程：S0 → S1 → S2 → ... → END")
    void fullCbtFlow() {
        // S0 → S1
        var r1 = sm.evaluate(CbtState.S0_START, "user_engaged", null);
        assertTrue(r1.transitioned());
        assertEquals(CbtState.S1_SAFETY_PRECHECK, r1.toState());

        // S1 → S2 (risk_safe)
        var r2 = sm.evaluate(CbtState.S1_SAFETY_PRECHECK, "risk_safe", null);
        assertEquals(CbtState.S2_EMOTION_LABEL, r2.toState());

        // S2 → S3
        var r3 = sm.evaluate(CbtState.S2_EMOTION_LABEL, "emotion_obtained", null);
        assertEquals(CbtState.S3_SCENARIO_ROUTE, r3.toState());

        // S3 → S4
        var r4 = sm.evaluate(CbtState.S3_SCENARIO_ROUTE, "scenario_matched", null);
        assertEquals(CbtState.S4_EVENT_FACT, r4.toState());

        // S4 → S5
        var r5 = sm.evaluate(CbtState.S4_EVENT_FACT, "event_confirmed", null);
        assertEquals(CbtState.S5_AUTO_THOUGHT, r5.toState());

        // S5 → S6
        var r6 = sm.evaluate(CbtState.S5_AUTO_THOUGHT, "thought_identified", null);
        assertEquals(CbtState.S6_REFRAME, r6.toState());

        // S6 → S7
        var r7 = sm.evaluate(CbtState.S6_REFRAME, "balanced_thought", null);
        assertEquals(CbtState.S7_MICRO_ACTION, r7.toState());

        // S7 → S8
        var r8 = sm.evaluate(CbtState.S7_MICRO_ACTION, "action_selected", null);
        assertEquals(CbtState.S8_RECHECK_CLOSE, r8.toState());

        // S8 → END (risk_stable)
        var r9 = sm.evaluate(CbtState.S8_RECHECK_CLOSE, "risk_stable", null);
        assertEquals(CbtState.END, r9.toState());
    }

    @Test
    @DisplayName("全局风险覆盖：任意状态 + RED → 强制 S9_ESCALATE")
    void globalRiskOverrideRed() {
        var result = sm.evaluate(CbtState.S4_EVENT_FACT, "event_confirmed", RiskLevel.RED);
        assertTrue(result.forced());
        assertEquals(CbtState.S9_ESCALATE, result.toState());
        assertEquals("global_risk_override", result.trigger());
    }

    @Test
    @DisplayName("全局风险覆盖：ORANGE 也触发强制升级")
    void globalRiskOverrideOrange() {
        var result = sm.evaluate(CbtState.S2_EMOTION_LABEL, "emotion_obtained", RiskLevel.ORANGE);
        assertTrue(result.forced());
        assertEquals(CbtState.S9_ESCALATE, result.toState());
    }

    @Test
    @DisplayName("YELLOW 风险不触发全局覆盖")
    void yellowRiskNoOverride() {
        var result = sm.evaluate(CbtState.S2_EMOTION_LABEL, "emotion_obtained", RiskLevel.YELLOW);
        assertFalse(result.forced());
        assertEquals(CbtState.S3_SCENARIO_ROUTE, result.toState());
    }

    @Test
    @DisplayName("S9_ESCALATE 是终态，不受全局覆盖影响也不转移")
    void escalateStateNoOverride() {
        var result = sm.evaluate(CbtState.S9_ESCALATE, "notification_sent", RiskLevel.RED);
        // S9 是终态，不再转移
        assertFalse(result.transitioned());
        assertEquals(CbtState.S9_ESCALATE, result.toState());
    }

    @Test
    @DisplayName("终态 END 不再转移")
    void terminalStateNoTransition() {
        var result = sm.evaluate(CbtState.END, "user_engaged", null);
        assertFalse(result.transitioned());
        assertEquals(CbtState.END, result.toState());
        assertEquals("terminal_state", result.trigger());
    }

    @Test
    @DisplayName("无匹配 trigger 保持当前状态")
    void noMatchingTriggerStays() {
        var result = sm.evaluate(CbtState.S2_EMOTION_LABEL, "invalid_trigger", null);
        assertFalse(result.transitioned());
        assertEquals(CbtState.S2_EMOTION_LABEL, result.toState());
    }

    @Test
    @DisplayName("S1 风险高时走 S9 分支")
    void safetyPrecheckRiskHigh() {
        var result = sm.evaluate(CbtState.S1_SAFETY_PRECHECK, "risk_high", null);
        assertTrue(result.transitioned());
        assertEquals(CbtState.S9_ESCALATE, result.toState());
    }

    @Test
    @DisplayName("S8 风险升级走 S9 分支")
    void recheckRiskEscalated() {
        var result = sm.evaluate(CbtState.S8_RECHECK_CLOSE, "risk_escalated", null);
        assertEquals(CbtState.S9_ESCALATE, result.toState());
    }

    @ParameterizedTest
    @EnumSource(CbtState.class)
    @DisplayName("所有状态 + null risk 不抛异常")
    void allStatesNoException(CbtState state) {
        assertDoesNotThrow(() -> sm.evaluate(state, "test", null));
    }
}
