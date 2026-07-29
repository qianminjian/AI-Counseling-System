package com.mindsafe.ai.orchestrator;

import com.mindsafe.common.enums.RiskLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 提示词编排引擎（ORCH-001/002，design/44 §四）
 * <p>
 * "先算策略、再拼提示词"：输入上下文信号 → 结构化 {@link StrategyProfile} →
 * 由 EMO-001 模板渲染为 System Prompt 情绪策略层片段。
 * <p>
 * 编排流水线（design/44 §4.2）：
 * <ol>
 *   <li>合规裁决：riskLevel 橙/红 → 锁定安全策略，短路一切个性化（优先级铁律最高位）</li>
 *   <li>情绪门控：currentEmotion（轮级）优先，缺失时用 entryMood（会话级）→ EmotionState</li>
 *   <li>语言层：effectiveGrade 由调用方计算（含 design/29 动态降级），本层透传</li>
 *   <li>情绪策略：{@link EntryMoodStrategyResolver} → 开场/节奏/技能/禁忌/镜映</li>
 *   <li>画像微调（PROF-022，design/46 §5.1）：置信门控后 introversion→开场/节奏、
 *       dominant_interests→镜映取材；低置信不参与，严禁覆盖合规与情绪门控</li>
 * </ol>
 * 纯规则计算，零额外 LLM 调用。
 * <p>
 * DEC-CBT 对齐：本服务不依赖主线管线，世界B（{@link ConversationOrchestrator}）激活时
 * 可直接复用 resolve() 作为策略层，避免第三套编排。
 */
@Service
public class PromptOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(PromptOrchestrationService.class);

    private final EntryMoodStrategyResolver moodResolver;

    public PromptOrchestrationService(EntryMoodStrategyResolver moodResolver) {
        this.moodResolver = moodResolver;
    }

    /**
     * 计算本轮策略档案
     */
    public StrategyProfile resolve(OrchestrationContext ctx) {
        boolean degraded = ctx.effectiveGrade() < ctx.grade();

        // 1. 合规裁决（design/44 §4.4 铁律最高位）：橙/红 → 安全策略短路，
        //    情绪/年龄/性格/画像全部让位；安全话术不降级（design/29 §3.11）
        if (ctx.riskLevel() != null && ctx.riskLevel().severity() >= RiskLevel.ORANGE.severity()) {
            log.info("编排合规裁决短路: riskLevel={}, 安全策略锁定", ctx.riskLevel());
            return new StrategyProfile(
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
        }

        // 2. 情绪来源：轮级 currentEmotion 优先（会话内漂移，design/44 §7.2），缺失回退会话级 entryMood
        String rawMood = ctx.currentEmotion() != null && !ctx.currentEmotion().isBlank()
                ? ctx.currentEmotion() : ctx.entryMood();
        String mood = moodResolver.normalize(rawMood);

        // 3. 情绪策略映射 + 门控（design/44 §5.2/§5.4）
        EntryMoodStrategyResolver.MoodStrategy strategy = moodResolver.resolve(mood);
        String mirrorHint = moodResolver.mirrorHint(mood, ctx.effectiveGrade());

        // 4. 画像微调（PROF-022）：优先级铁律"合规 > 情绪 > 年龄 > 性格 > 画像 > 兴趣"——
        //    仅稳定态允许 introversion 调整开场/节奏（激活态由情绪策略主导，不覆盖）；
        //    低置信维度不参与（design/46 §5.2：宁可不用，不可乱用）
        StrategyProfile.OpeningStrategy opening = strategy.opening();
        StrategyProfile.Pace pace = strategy.pace();
        ProfileSignals signals = ctx.profileSignals();
        if (signals != null) {
            if (signals.introversionUsable() && signals.introversion() >= 0.7
                    && strategy.emotionState() == StrategyProfile.EmotionState.STABLE) {
                // 内向偏高：稳定态下改留白低压开场 + 放慢节奏（不追问、给选择、允许沉默）
                opening = StrategyProfile.OpeningStrategy.LOW_PRESSURE_SPACE;
                pace = StrategyProfile.Pace.SLOW;
            }
            if (signals.interestsUsable()) {
                String material = "孩子喜欢「" + String.join("、",
                        signals.dominantInterests().stream().limit(3).toList()) + "」，镜映比喻可优先从这些主题取材";
                mirrorHint = mirrorHint.isBlank() ? material : mirrorHint + "；" + material;
            }
        }

        return new StrategyProfile(
                ctx.effectiveGrade(),
                strategy.emotionState(),
                mood,
                opening,
                pace,
                strategy.skillPriority(),
                strategy.forbiddenActions(),
                mirrorHint,
                strategy.allowCbt(),
                degraded,
                false);
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
