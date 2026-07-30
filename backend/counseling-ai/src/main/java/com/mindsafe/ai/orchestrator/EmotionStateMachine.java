package com.mindsafe.ai.orchestrator;

import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 情绪状态机（ORCH-003，design/44 §7.1/§7.2）
 * <p>
 * 会话内三态转移：STABLE ↔ ACTIVATED → CRISIS。
 * <ul>
 *   <li>STABLE：可推进（含 CBT，按年龄）</li>
 *   <li>ACTIVATED：情绪优先，压制 CBT/追问，走稳定策略</li>
 *   <li>CRISIS：交风险管线，编排让位（不自动回落，需教师处置/新会话）</li>
 * </ul>
 * <p>
 * 漂移规则（§7.2）：
 * <ul>
 *   <li>升级（sad→crisis、calm→anxious）：立即切换，不等下一会话</li>
 *   <li>缓解（anxious→calm 持续 ≥2 轮）：解除情绪门控，allowCbt 可转 true</li>
 *   <li>CRISIS 不自动回落（风险管线接管后由外部重置）</li>
 * </ul>
 * 纯规则，零 LLM。
 */
@Component
public class EmotionStateMachine {

    /** 需要 ≥ 该轮数持续缓解才从 ACTIVATED 降回 STABLE（design/44 §7.2） */
    private static final int RELIEF_THRESHOLD = 2;

    /** ACTIVATED 态情绪集（高唤醒/负性） */
    private static final Set<String> ACTIVATED_MOODS = Set.of(
            "sad", "angry", "fearful", "anxious", "withdrawn");

    /**
     * 状态转移结果。
     *
     * @param state       转移后的情绪状态
     * @param reliefCount 当前连续缓解轮数（非 ACTIVATED→STABLE 转移时归零）
     */
    public record Transition(StrategyProfile.EmotionState state, int reliefCount) {
    }

    /**
     * 计算本轮状态转移。
     *
     * @param previousState   上一轮状态（首轮为 STABLE）
     * @param previousRelief  上一轮连续缓解计数
     * @param currentMood     本轮规范情绪标签（经 normalize 后）
     * @param riskEscalated   本轮是否检出风险升级（橙/红 → 强制 CRISIS）
     * @return 转移结果（新状态 + 更新后的缓解计数）
     */
    public Transition transition(StrategyProfile.EmotionState previousState,
                                 int previousRelief,
                                 String currentMood,
                                 boolean riskEscalated) {
        // 风险升级 → 强制 CRISIS（不自动回落）
        if (riskEscalated || "crisis".equals(currentMood)) {
            return new Transition(StrategyProfile.EmotionState.CRISIS, 0);
        }

        // CRISIS 态不自动回落（需外部重置：教师处置/新会话）
        if (previousState == StrategyProfile.EmotionState.CRISIS) {
            return new Transition(StrategyProfile.EmotionState.CRISIS, 0);
        }

        // 高唤醒/负性 → 立即升级到 ACTIVATED，缓解计数归零
        if (currentMood != null && ACTIVATED_MOODS.contains(currentMood)) {
            return new Transition(StrategyProfile.EmotionState.ACTIVATED, 0);
        }

        // 缓解态（calm/happy/其他非激活情绪）
        if (previousState == StrategyProfile.EmotionState.ACTIVATED) {
            int relief = previousRelief + 1;
            if (relief >= RELIEF_THRESHOLD) {
                // 持续缓解达标 → 降回 STABLE，解除门控
                return new Transition(StrategyProfile.EmotionState.STABLE, 0);
            }
            // 尚在缓解观察期，维持 ACTIVATED（门控不解除）
            return new Transition(StrategyProfile.EmotionState.ACTIVATED, relief);
        }

        // STABLE 维持
        return new Transition(StrategyProfile.EmotionState.STABLE, 0);
    }
}
