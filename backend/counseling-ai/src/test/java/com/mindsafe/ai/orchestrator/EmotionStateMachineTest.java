package com.mindsafe.ai.orchestrator;

import com.mindsafe.ai.orchestrator.StrategyProfile.EmotionState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EmotionStateMachine 单元测试（ORCH-003，design/44 §7.1/§7.2/§十二）
 * <p>
 * 覆盖：升级立即切换、缓解≥2轮才解除、CRISIS不自动回落、风险强制CRISIS。
 */
class EmotionStateMachineTest {

    private final EmotionStateMachine sm = new EmotionStateMachine();

    @Test
    @DisplayName("STABLE + sad → 立即升级 ACTIVATED")
    void stable_to_activated() {
        var t = sm.transition(EmotionState.STABLE, 0, "sad", false);
        assertThat(t.state()).isEqualTo(EmotionState.ACTIVATED);
        assertThat(t.reliefCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("STABLE + calm → 维持 STABLE")
    void stable_stays() {
        var t = sm.transition(EmotionState.STABLE, 0, "calm", false);
        assertThat(t.state()).isEqualTo(EmotionState.STABLE);
    }

    @Test
    @DisplayName("ACTIVATED + calm 第1轮 → 仍 ACTIVATED（缓解观察期）")
    void activated_relief_round1() {
        var t = sm.transition(EmotionState.ACTIVATED, 0, "calm", false);
        assertThat(t.state()).isEqualTo(EmotionState.ACTIVATED);
        assertThat(t.reliefCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("ACTIVATED + calm 第2轮 → 降回 STABLE（缓解达标）")
    void activated_relief_round2_stable() {
        var t = sm.transition(EmotionState.ACTIVATED, 1, "calm", false);
        assertThat(t.state()).isEqualTo(EmotionState.STABLE);
        assertThat(t.reliefCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("ACTIVATED + happy 第2轮 → 也降回 STABLE（happy 也是缓解）")
    void activated_happy_relief() {
        var t = sm.transition(EmotionState.ACTIVATED, 1, "happy", false);
        assertThat(t.state()).isEqualTo(EmotionState.STABLE);
    }

    @Test
    @DisplayName("ACTIVATED 缓解期中途再激活 → 计数归零重计")
    void activated_relief_reset() {
        // 第1轮缓解
        var t1 = sm.transition(EmotionState.ACTIVATED, 0, "calm", false);
        assertThat(t1.reliefCount()).isEqualTo(1);
        // 第2轮又 sad → 重新 ACTIVATED，计数归零
        var t2 = sm.transition(t1.state(), t1.reliefCount(), "sad", false);
        assertThat(t2.state()).isEqualTo(EmotionState.ACTIVATED);
        assertThat(t2.reliefCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("ACTIVATED + crisis → 强制 CRISIS")
    void activated_to_crisis() {
        var t = sm.transition(EmotionState.ACTIVATED, 0, "crisis", false);
        assertThat(t.state()).isEqualTo(EmotionState.CRISIS);
    }

    @Test
    @DisplayName("riskEscalated=true → 无论情绪一律 CRISIS")
    void risk_escalated_forces_crisis() {
        var t = sm.transition(EmotionState.STABLE, 0, "happy", true);
        assertThat(t.state()).isEqualTo(EmotionState.CRISIS);
    }

    @Test
    @DisplayName("CRISIS 不自动回落（即使情绪变 calm）")
    void crisis_no_auto_fallback() {
        var t = sm.transition(EmotionState.CRISIS, 0, "calm", false);
        assertThat(t.state()).isEqualTo(EmotionState.CRISIS);
    }

    @Test
    @DisplayName("CRISIS + 再次风险升级 → 仍 CRISIS")
    void crisis_stays_on_risk() {
        var t = sm.transition(EmotionState.CRISIS, 0, "sad", true);
        assertThat(t.state()).isEqualTo(EmotionState.CRISIS);
    }

    @Test
    @DisplayName("withdrawn 也触发 ACTIVATED")
    void withdrawn_activates() {
        var t = sm.transition(EmotionState.STABLE, 0, "withdrawn", false);
        assertThat(t.state()).isEqualTo(EmotionState.ACTIVATED);
    }

    @Test
    @DisplayName("null mood 不触发 ACTIVATED（视为无信号）")
    void null_mood_stable() {
        var t = sm.transition(EmotionState.STABLE, 0, null, false);
        assertThat(t.state()).isEqualTo(EmotionState.STABLE);
    }
}
