package com.mindsafe.ai.cbt;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * CBT 阶段标记 + 年龄分层路由（CBT-201/202，design/03 §11.3/11.4，design/52 §一）
 * <p>
 * CBT-201：结构化阶段标记，供 design/45 评估闭环使用。
 * CBT-202：年龄分层技能路由（低龄行为激活 vs 高龄认知重构）。
 */
@Component
public class CbtStageRouter {

    /** CBT 对话阶段（design/03 §六） */
    public enum CbtStage {
        RAPPORT,            // 建立关系
        PROBLEM_IDENTIFY,   // 问题识别
        THOUGHT_RECORD,     // 思维记录（自动思维捕捉）
        COGNITIVE_RESTRUCTURE, // 认知重构
        BEHAVIORAL_ACTIVATION, // 行为激活
        SKILL_PRACTICE,     // 技能练习
        RELAPSE_PREVENTION, // 复发预防
        CLOSURE             // 收束
    }

    /** 年龄分层策略 */
    public enum AgeStrategy {
        BEHAVIORAL_FIRST,   // 低龄（1-2 年级）：行为激活优先，简化认知
        BALANCED,           // 中龄（3-4 年级）：行为+认知并重
        COGNITIVE_FIRST     // 高龄（5-6 年级）：认知重构优先
    }

    /** 阶段标记结果 */
    public record StageMark(
            CbtStage stage,
            AgeStrategy ageStrategy,
            List<String> allowedTechniques,
            boolean allowCbt
    ) {
    }

    /**
     * 根据年级决定年龄分层策略。
     */
    public AgeStrategy resolveAgeStrategy(int effectiveGrade) {
        if (effectiveGrade <= 2) return AgeStrategy.BEHAVIORAL_FIRST;
        if (effectiveGrade <= 4) return AgeStrategy.BALANCED;
        return AgeStrategy.COGNITIVE_FIRST;
    }

    /**
     * 根据阶段+年龄策略生成阶段标记。
     *
     * @param stage          当前 CBT 阶段
     * @param effectiveGrade 有效年级
     * @param allowCbt       是否允许 CBT（情绪状态机门控）
     * @return 阶段标记
     */
    public StageMark mark(CbtStage stage, int effectiveGrade, boolean allowCbt) {
        AgeStrategy strategy = resolveAgeStrategy(effectiveGrade);
        List<String> techniques = resolveTechniques(stage, strategy);
        return new StageMark(stage, strategy, techniques, allowCbt);
    }

    /**
     * 根据阶段+年龄策略解析允许的技术列表。
     */
    private List<String> resolveTechniques(CbtStage stage, AgeStrategy strategy) {
        if (strategy == AgeStrategy.BEHAVIORAL_FIRST) {
            // 低龄：行为激活+情绪外化，不做认知重构
            return switch (stage) {
                case RAPPORT -> List.of("playful_intro", "emotion_faces");
                case PROBLEM_IDENTIFY -> List.of("emotion_thermometer", "story_metaphor");
                case BEHAVIORAL_ACTIVATION -> List.of("micro_action", "reward_chart");
                case SKILL_PRACTICE -> List.of("breathing", "grounding");
                case CLOSURE -> List.of("sticker_reward", "next_time_preview");
                default -> List.of("emotion_labeling", "behavioral_prompt");
            };
        }
        if (strategy == AgeStrategy.COGNITIVE_FIRST) {
            // 高龄：认知重构优先
            return switch (stage) {
                case RAPPORT -> List.of("rapport_talk", "agenda_setting");
                case PROBLEM_IDENTIFY -> List.of("situation_analysis", "emotion_link");
                case THOUGHT_RECORD -> List.of("auto_thought_capture", "evidence_exam");
                case COGNITIVE_RESTRUCTURE -> List.of("balanced_thought", "perspective_shift");
                case RELAPSE_PREVENTION -> List.of("coping_card", "warning_signs");
                case CLOSURE -> List.of("session_summary", "homework_assign");
                default -> List.of("socratic_question", "thought_diary");
            };
        }
        // BALANCED：中龄并重
        return switch (stage) {
            case RAPPORT -> List.of("rapport_talk", "emotion_check");
            case PROBLEM_IDENTIFY -> List.of("emotion_thermometer", "situation_analysis");
            case THOUGHT_RECORD -> List.of("simple_thought_capture");
            case BEHAVIORAL_ACTIVATION -> List.of("micro_action", "activity_schedule");
            case SKILL_PRACTICE -> List.of("breathing", "positive_self_talk");
            case CLOSURE -> List.of("recap", "hope_anchor");
            default -> List.of("emotion_labeling", "guided_discovery");
        };
    }
}
