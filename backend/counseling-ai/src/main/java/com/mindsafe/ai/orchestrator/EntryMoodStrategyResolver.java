package com.mindsafe.ai.orchestrator;

import com.mindsafe.ai.orchestrator.StrategyProfile.OpeningStrategy;
import com.mindsafe.ai.orchestrator.StrategyProfile.Pace;
import com.mindsafe.ai.orchestrator.StrategyProfile.SkillPriority;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 进入心情策略解析器（ORCH-001，design/44 §5.2/§5.3）
 * <p>
 * 规范情绪集（design/44 §5.1）：calm/happy/anxious/sad/angry/fearful/withdrawn/crisis。
 * 学生端 5 情绪（happy/sad/angry/scared/nervous）与语音 SER 标签先经 {@link #normalize} 归一。
 * <p>
 * 纯规则映射，零 LLM 调用（design/44 目标 6）。
 */
@Component
public class EntryMoodStrategyResolver {

    /** 单条情绪策略（design/44 §5.2 映射表一行） */
    public record MoodStrategy(
            StrategyProfile.EmotionState emotionState,
            OpeningStrategy opening,
            Pace pace,
            SkillPriority skillPriority,
            List<String> forbiddenActions,
            boolean allowCbt
    ) {
    }

    /**
     * 情绪标签归一化：学生端/语音标签 → design/44 §5.1 规范集。
     * 未知/缺失一律回退 calm（不猜测情绪，宁保守）。
     */
    public String normalize(String rawMood) {
        if (rawMood == null || rawMood.isBlank()) {
            return "calm";
        }
        return switch (rawMood) {
            // 规范集自身直接透传
            case "calm", "happy", "anxious", "sad", "angry", "fearful", "withdrawn", "crisis" -> rawMood;
            // 学生端进入心情标签（buildGreeting 同源）
            case "scared" -> "fearful";
            case "nervous" -> "anxious";
            default -> "calm";
        };
    }

    /**
     * VCL-001：语音 SER 9 类标签 → 规范集映射（design/47 §4.1）。
     * <p>
     * 与 {@link #normalize} 语义不同：normalize 兜底 calm（"不猜测宁保守"用于必填场景），
     * 本方法对 surprised/other/unknown 返回 null——表示"本轮语音信号不可用"，
     * 由编排层回退会话级 entryMood，而不是把未知信号错当平静驱动策略。
     *
     * @param serLabel voice-service emotion2vec 输出标签
     * @return 规范集情绪（calm/happy/sad/angry/fearful），不可映射时 null
     */
    public String mapVoiceEmotion(String serLabel) {
        if (serLabel == null || serLabel.isBlank()) {
            return null;
        }
        return switch (serLabel) {
            case "happy" -> "happy";
            case "neutral" -> "calm";
            case "sad" -> "sad";
            case "fearful" -> "fearful";
            // disgusted 在儿童语音场景多为强烈不满/抵触，并入 angry 策略（design/47 §4.1）
            case "angry", "disgusted" -> "angry";
            // surprised 情绪极性不明（可惊喜可惊吓），单靠语音不驱动策略，回退 entryMood
            default -> null;
        };
    }

    /**
     * 规范情绪 → 开场/节奏/技能/禁忌策略（design/44 §5.2 映射表）
     *
     * @param canonicalMood 规范化后的情绪标签
     */
    public MoodStrategy resolve(String canonicalMood) {
        return switch (canonicalMood) {
            case "happy" -> new MoodStrategy(
                    StrategyProfile.EmotionState.STABLE,
                    OpeningStrategy.NORMAL_ADVANCE, Pace.NORMAL, SkillPriority.POSITIVE_AMPLIFY,
                    List.of("不强行找问题", "不扫兴"),
                    true);
            case "anxious" -> new MoodStrategy(
                    StrategyProfile.EmotionState.ACTIVATED,
                    OpeningStrategy.STABILIZE_FIRST, Pace.SLOW, SkillPriority.PFA_GROUNDING,
                    List.of("不立刻追问事件细节", "不做认知重构", "不堆信息"),
                    false);
            case "sad" -> new MoodStrategy(
                    StrategyProfile.EmotionState.ACTIVATED,
                    OpeningStrategy.HOLD_EMOTION, Pace.SLOW, SkillPriority.LISTEN_EMPATHY,
                    List.of("不说\"别难过\"", "不急于解决问题", "不讲道理"),
                    false);
            case "angry" -> new MoodStrategy(
                    StrategyProfile.EmotionState.ACTIVATED,
                    OpeningStrategy.HOLD_EMOTION, Pace.SLOW, SkillPriority.LISTEN_VENT,
                    List.of("不说教", "不评判对错", "不站队"),
                    false);
            case "fearful" -> new MoodStrategy(
                    StrategyProfile.EmotionState.ACTIVATED,
                    OpeningStrategy.STABILIZE_FIRST, Pace.SLOW, SkillPriority.PFA_SAFETY,
                    List.of("不追问恐惧源", "不放大威胁"),
                    false);
            case "withdrawn" -> new MoodStrategy(
                    StrategyProfile.EmotionState.ACTIVATED,
                    OpeningStrategy.LOW_PRESSURE_SPACE, Pace.SLOW, SkillPriority.COMPANION_SPACE,
                    List.of("不追问", "不劝说", "不连续提问（单轮提问不超过 1 个）"),
                    false);
            case "crisis" -> new MoodStrategy(
                    StrategyProfile.EmotionState.CRISIS,
                    OpeningStrategy.STABILIZE_FIRST, Pace.SLOW, SkillPriority.CRISIS_HANDLING,
                    List.of("禁止 CBT/认知重构", "禁止探索/深挖", "禁止追问细节"),
                    false);
            // calm 及归一化兜底
            default -> new MoodStrategy(
                    StrategyProfile.EmotionState.STABLE,
                    OpeningStrategy.NORMAL_ADVANCE, Pace.NORMAL, SkillPriority.SEL_FIRST,
                    List.of(),
                    true);
        };
    }

    /**
     * 情绪镜映话术取材（design/44 §5.3：情绪 × 年级段，命名即驯服）。
     * 表中未覆盖的情绪（calm/happy/withdrawn/crisis）不做镜映，返回空串。
     */
    public String mirrorHint(String canonicalMood, int effectiveGrade) {
        String band = effectiveGrade <= 2 ? "low" : effectiveGrade <= 4 ? "mid" : "high";
        return switch (canonicalMood) {
            case "anxious" -> switch (band) {
                case "low" -> "心里是不是像有小鹿在乱撞呀？";
                case "mid" -> "听起来心里有点七上八下的";
                default -> "有点紧张、放不下心，对吗？";
            };
            case "sad" -> switch (band) {
                case "low" -> "你现在是不是像下雨的小天空？";
                case "mid" -> "心里像背了个重重的书包";
                default -> "感觉有点提不起劲、有点闷";
            };
            case "angry" -> switch (band) {
                case "low" -> "你是不是像小火山有点想喷发？";
                case "mid" -> "这件事让你很不服气对吧";
                default -> "被这样对待，生气很正常";
            };
            case "fearful" -> switch (band) {
                case "low" -> "有点怕怕的？波波牵着你的手";
                case "mid" -> "有点担心会发生不好的事？";
                default -> "这种不确定让你没有安全感";
            };
            default -> "";
        };
    }
}
