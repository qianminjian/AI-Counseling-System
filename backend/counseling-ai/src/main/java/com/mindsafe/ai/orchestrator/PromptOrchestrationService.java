package com.mindsafe.ai.orchestrator;

import com.mindsafe.common.enums.RiskLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 提示词编排引擎（ORCH-001/002/003/005，design/44 §四/§七）
 * <p>
 * “先算策略、再拼提示词”：输入上下文信号 → 结构化 {@link StrategyProfile} →
 * 由 EMO-001 模板渲染为 System Prompt 情绪策略层片段。
 * <p>
 * 编排流水线（design/44 §4.2）：
 * <ol>
 *   <li>合规裁决：riskLevel 橙/红 → 锁定安全策略，短路一切个性化（优先级铁律最高位）</li>
 *   <li>情绪状态机（ORCH-003）：轮级漂移检测 + 缓解门控（≥2轮才解除）</li>
 *   <li>情绪门控：currentEmotion（轮级）优先，缺失时用 entryMood（会话级）→ EmotionState</li>
 *   <li>语言层：effectiveGrade 由调用方计算（含 design/29 动态降级），本层透传</li>
 *   <li>情绪策略：{@link EntryMoodStrategyResolver} → 开场/节奏/技能/禁忌/镜映</li>
 *   <li>画像微调（PROF-025）：置信门控后 introversion→开场/节奏、interests→镜映取材</li>
 *   <li>冷场协同（ORCH-005）：nudge 触发时偏向留白低压策略（design/28 并入编排）</li>
 * </ol>
 * 纯规则计算，零额外 LLM 调用。
 */
@Service
public class PromptOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(PromptOrchestrationService.class);

    private final EntryMoodStrategyResolver moodResolver;
    private final EmotionStateMachine stateMachine;

    public PromptOrchestrationService(EntryMoodStrategyResolver moodResolver,
                                      EmotionStateMachine stateMachine) {
        this.moodResolver = moodResolver;
        this.stateMachine = stateMachine;
    }

    /**
     * 编排结果（策略档案 + 状态机转移，供调用方持久化会话级状态）。
     */
    public record Result(StrategyProfile profile, EmotionStateMachine.Transition transition) {
    }

    /**
     * 计算本轮策略档案（向后兼容，不返回状态转移）
     */
    public StrategyProfile resolve(OrchestrationContext ctx) {
        return resolveWithTransition(ctx).profile();
    }
    
    /**
     * 计算本轮策略档案 + 状态机转移（ORCH-003）
     */
    public Result resolveWithTransition(OrchestrationContext ctx) {
        boolean degraded = ctx.effectiveGrade() < ctx.grade();
        boolean riskEscalated = ctx.riskLevel() != null
                && ctx.riskLevel().severity() >= RiskLevel.ORANGE.severity();
    
        // 1. 合规裁决（design/44 §4.4 铁律最高位）
        if (riskEscalated) {
            log.info("编排合规裁决短路: riskLevel={}, 安全策略锁定", ctx.riskLevel());
            var transition = new EmotionStateMachine.Transition(
                    StrategyProfile.EmotionState.CRISIS, 0);
            var profile = new StrategyProfile(
                    ctx.grade(),
                    StrategyProfile.EmotionState.CRISIS,
                    moodResolver.normalize(ctx.entryMood()),
                    StrategyProfile.OpeningStrategy.STABILIZE_FIRST,
                    StrategyProfile.Pace.SLOW,
                    StrategyProfile.SkillPriority.CRISIS_HANDLING,
                    List.of("禁止 CBT/认知重构", "禁止探索/深挖", "禁止追问细节", "禁止一切个性化玩笑与转移话题"),
                    "",
                    false,
                    false,
                    true);
            return new Result(profile, transition);
        }
    
        // 2. 情绪来源：轮级 currentEmotion 优先，缺失回退会话级 entryMood
        String rawMood = ctx.currentEmotion() != null && !ctx.currentEmotion().isBlank()
                ? ctx.currentEmotion() : ctx.entryMood();
        String mood = moodResolver.normalize(rawMood);
    
        // 3. 情绪状态机转移（ORCH-003，design/44 §7.2）
        EmotionStateMachine.Transition transition = stateMachine.transition(
                ctx.previousEmotionState(), ctx.previousReliefCount(), mood, false);
    
        // 4. 情绪策略映射 + 门控
        EntryMoodStrategyResolver.MoodStrategy strategy = moodResolver.resolve(mood);
        String mirrorHint = moodResolver.mirrorHint(mood, ctx.effectiveGrade());
    
        // 状态机可能覆盖 MoodStrategy 的 emotionState（如缓解期维持 ACTIVATED 门控）
        StrategyProfile.EmotionState effectiveState = transition.state();
        boolean allowCbt = strategy.allowCbt() && effectiveState == StrategyProfile.EmotionState.STABLE;
    
        // 5. 画像微调（PROF-025 + ORCH-006）
        StrategyProfile.OpeningStrategy opening = strategy.opening();
        StrategyProfile.Pace pace = strategy.pace();
        ProfileSignals signals = ctx.profileSignals();
        if (signals != null) {
            if (signals.introversionUsable() && signals.introversion() >= 0.7
                    && effectiveState == StrategyProfile.EmotionState.STABLE) {
                opening = StrategyProfile.OpeningStrategy.LOW_PRESSURE_SPACE;
                pace = StrategyProfile.Pace.SLOW;
            }
            if (signals.interestsUsable()) {
                String material = "孩子喜欢「" + String.join("、",
                        signals.dominantInterests().stream().limit(3).toList()) + "」，镜映比喻可优先从这些主题取材";
                mirrorHint = mirrorHint.isBlank() ? material : mirrorHint + "；" + material;
            }
            // ORCH-006：高敏感→更温柔慢，追问强度降低
            if (signals.sensitivityUsable() && signals.sensitivity() >= 0.7
                    && effectiveState == StrategyProfile.EmotionState.STABLE) {
                pace = StrategyProfile.Pace.SLOW;
                mirrorHint = mirrorHint.isBlank()
                        ? "孩子较敏感，镜映话术要更温柔、不评判"
                        : mirrorHint + "；孩子较敏感，话术温度要更高";
            }
            // ORCH-006：高好奇→可用探索式引导（不直接给答案）
            if (signals.curiosityUsable() && signals.curiosity() >= 0.7
                    && effectiveState == StrategyProfile.EmotionState.STABLE && allowCbt) {
                mirrorHint = mirrorHint.isBlank()
                        ? "孩子好奇心强，可用探索式提问引导自己发现（而非直接告知）"
                        : mirrorHint + "；可用探索式提问引导";
            }
            // ORCH-006：已掌握技巧→可主动唤起复用
            if (signals.copingSkillsUsable() && effectiveState != StrategyProfile.EmotionState.CRISIS) {
                String skills = String.join("、", signals.copingSkills().stream().limit(3).toList());
                mirrorHint = mirrorHint.isBlank()
                        ? "孩子已掌握「" + skills + "」，情绪波动时可温和唤起复用"
                        : mirrorHint + "；已掌握技巧：" + skills + "，可唤起复用";
            }
        }
    
        // 6. 冷场协同（ORCH-005，design/44 §7.3）：nudge 触发时偏向留白低压
        if (ctx.coldStartNudge() && effectiveState != StrategyProfile.EmotionState.CRISIS) {
            opening = StrategyProfile.OpeningStrategy.LOW_PRESSURE_SPACE;
            pace = StrategyProfile.Pace.SLOW;
        }

        // 7. 高敏模式（SAFE-202）：话题敏感时强制慢节奏+禁追问（不短路 LLM，只调策略）
        List<String> forbidden = strategy.forbiddenActions();
        if (ctx.highSensitivity() && effectiveState != StrategyProfile.EmotionState.CRISIS) {
            pace = StrategyProfile.Pace.SLOW;
            forbidden = new java.util.ArrayList<>(forbidden);
            forbidden.add("不主动追问事件细节（高敏话题，等孩子主动说）");
        }
    
        var profile = new StrategyProfile(
                ctx.effectiveGrade(),
                effectiveState,
                mood,
                opening,
                pace,
                strategy.skillPriority(),
                forbidden,
                mirrorHint,
                allowCbt,
                degraded,
                false);
        return new Result(profile, transition);
    }

    /**
     * VCL-001：语音 SER 标签 → 规范集情绪（委托 {@link EntryMoodStrategyResolver#mapVoiceEmotion}）。
     * 调用方拿映射结果填 {@link OrchestrationContext#currentEmotion()}；null 表示信号不可用，
     * resolve() 会自然回退 entryMood。
     */
    public String mapVoiceEmotion(String serLabel) {
        return moodResolver.mapVoiceEmotion(serLabel);
    }

    /**
     * StrategyProfile → EMO-001 模板变量（design/45 §4.3 变量契约）
     * <p>
     * CBT 门控语句在此预计算（模板引擎仅支持 {@code {{var}}} 替换，不做条件逻辑）。
     */
    public Map<String, String> toTemplateVariables(StrategyProfile profile) {
        String cbtGate = profile.allowCbt()
                ? "情绪在窗口内，可按年龄温和推进认知工作（低年级只到\"感受+小行动\"，高年级可到认知三角）"
                : "本轮不进入认知重构，先做情绪调节（命名/接地/呼吸），情绪回到窗口内再考虑";
        String mirror = profile.emotionMirrorHint() == null || profile.emotionMirrorHint().isBlank()
                ? "无需刻意镜映，自然回应即可"
                : "可参考取材：" + profile.emotionMirrorHint();
        String forbidden = profile.forbiddenActions().isEmpty()
                ? "无特殊禁止"
                : String.join("；", profile.forbiddenActions());
        return Map.of(
                "emotion_state", profile.emotionState().label(),
                "entry_mood", profile.entryMood(),
                "opening", profile.opening().label(),
                "pace", profile.pace().label(),
                "mirror_hint", mirror,
                "skill_priority", profile.skillPriority().label(),
                "forbidden_actions", forbidden,
                "cbt_gate", cbtGate
        );
    }
}
