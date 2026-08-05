package com.mindsafe.ai.orchestrator;

import com.mindsafe.ai.orchestrator.StrategyProfile.EmotionState;
import com.mindsafe.ai.orchestrator.StrategyProfile.OpeningStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReplyEmotionResolver 单测（TTSFX-004 后端侧，design/37 §三.1 三方同源）
 * <p>
 * 契约：由编排策略档案（StrategyProfile）以纯规则推导 AI 回复情绪标签
 * （happy/gentle/encourage/calm/serious/soothe 六类），零 LLM。
 * 前端表情状态机/TTS/主题层经 SSE emotion 事件同源消费。
 */
class ReplyEmotionResolverTest {

    private final ReplyEmotionResolver resolver = new ReplyEmotionResolver();

    private StrategyProfile profile(EmotionState state, String entryMood,
                                    OpeningStrategy opening, boolean safetyLocked) {
        return new StrategyProfile(
                3, state, entryMood, opening,
                StrategyProfile.Pace.NORMAL, StrategyProfile.SkillPriority.SEL_FIRST,
                List.of(), null, state == EmotionState.STABLE, false, safetyLocked);
    }

    @Test
    @DisplayName("合规锁定（safetyLocked）→ serious（庄重处置，前端锁定 hug）")
    void safetyLockedMapsToSerious() {
        assertThat(resolver.resolve(profile(EmotionState.STABLE, "happy",
                OpeningStrategy.NORMAL_ADVANCE, true)).emotion()).isEqualTo("serious");
    }

    @Test
    @DisplayName("CRISIS 危机态 → serious")
    void crisisMapsToSerious() {
        assertThat(resolver.resolve(profile(EmotionState.CRISIS, "crisis",
                OpeningStrategy.STABILIZE_FIRST, false)).emotion()).isEqualTo("serious");
    }

    @Test
    @DisplayName("ACTIVATED + 留白低压 → calm（不施压的平静陪伴）")
    void activatedLowPressureMapsToCalm() {
        assertThat(resolver.resolve(profile(EmotionState.ACTIVATED, "withdrawn",
                OpeningStrategy.LOW_PRESSURE_SPACE, false)).emotion()).isEqualTo("calm");
    }

    @Test
    @DisplayName("ACTIVATED + 先接住情绪 → soothe（安抚）")
    void activatedHoldEmotionMapsToSoothe() {
        assertThat(resolver.resolve(profile(EmotionState.ACTIVATED, "sad",
                OpeningStrategy.HOLD_EMOTION, false)).emotion()).isEqualTo("soothe");
    }

    @Test
    @DisplayName("ACTIVATED + 先稳定 → soothe")
    void activatedStabilizeMapsToSoothe() {
        assertThat(resolver.resolve(profile(EmotionState.ACTIVATED, "anxious",
                OpeningStrategy.STABILIZE_FIRST, false)).emotion()).isEqualTo("soothe");
    }

    @Test
    @DisplayName("STABLE + 正常推进 + 积极情绪 → happy（一起放大积极体验）")
    void stablePositiveMapsToHappy() {
        assertThat(resolver.resolve(profile(EmotionState.STABLE, "happy",
                OpeningStrategy.NORMAL_ADVANCE, false)).emotion()).isEqualTo("happy");
    }

    @Test
    @DisplayName("STABLE + 正常推进 + 非积极情绪 → encourage（温和推进打气）")
    void stableAdvanceMapsToEncourage() {
        assertThat(resolver.resolve(profile(EmotionState.STABLE, "calm",
                OpeningStrategy.NORMAL_ADVANCE, false)).emotion()).isEqualTo("encourage");
    }

    @Test
    @DisplayName("STABLE + 非推进类开场 → gentle（轻柔回应）")
    void stableNonAdvanceMapsToGentle() {
        assertThat(resolver.resolve(profile(EmotionState.STABLE, "calm",
                OpeningStrategy.HOLD_EMOTION, false)).emotion()).isEqualTo("gentle");
    }

    @Test
    @DisplayName("失败安全：null 档案 → gentle（不猜测，柔和兜底）")
    void nullProfileFallsBackToGentle() {
        assertThat(resolver.resolve(null).emotion()).isEqualTo("gentle");
    }

    @Test
    @DisplayName("强度分级与情绪状态对齐：STABLE=1 / ACTIVATED=2 / CRISIS=3")
    void intensityFollowsEmotionState() {
        assertThat(resolver.resolve(profile(EmotionState.STABLE, "calm",
                OpeningStrategy.NORMAL_ADVANCE, false)).intensity()).isEqualTo(1);
        assertThat(resolver.resolve(profile(EmotionState.ACTIVATED, "sad",
                OpeningStrategy.HOLD_EMOTION, false)).intensity()).isEqualTo(2);
        assertThat(resolver.resolve(profile(EmotionState.CRISIS, "crisis",
                OpeningStrategy.STABILIZE_FIRST, false)).intensity()).isEqualTo(3);
    }
}
