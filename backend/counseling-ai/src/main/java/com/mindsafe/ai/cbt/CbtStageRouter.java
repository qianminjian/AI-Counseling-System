package com.mindsafe.ai.cbt;

import com.mindsafe.ai.orchestrator.StrategyProfile;
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
     * 阶段推断（WIRE-002/CBT-201，design/03 §11.4）。
     * <p>
     * 启发式：轮次作阶段代理 + 容纳之窗门控（design/03 §11.2）：
     * CRISIS/ACTIVATED 高唤醒态不进认知节点，降级为稳定化（SKILL_PRACTICE 呼吸/着陆）。
     * M2+ 由分诊段 LLM 回名替代本启发式。
     *
     * @param turn         当前轮次（从 1 起）
     * @param emotionState 情绪状态机当前态（容纳之窗门控输入）
     * @return 推断的 CBT 阶段
     */
    public CbtStage inferStage(int turn, StrategyProfile.EmotionState emotionState) {
        if (emotionState == StrategyProfile.EmotionState.CRISIS
                || emotionState == StrategyProfile.EmotionState.ACTIVATED) {
            return CbtStage.SKILL_PRACTICE;
        }
        if (turn <= 2) return CbtStage.RAPPORT;
        if (turn >= 9) return CbtStage.CLOSURE;
        return CbtStage.PROBLEM_IDENTIFY;
    }

    /**
     * 渲染阶段指令片段（追加到 system 层，WIRE-002）。
     * <p>
     * 优先级裁决在提示词之外完成（本方法只渲染 mark() 的裁决结果，不做新决策）。
     */
    public String stageDirective(StageMark mark) {
        if (!mark.allowCbt()) {
            return "【CBT 阶段指令】当前情绪高唤醒，先稳定化陪伴：只用呼吸/着陆/情绪命名技术，"
                    + "不讲道理、不追问、不做认知重构。";
        }
        StringBuilder sb = new StringBuilder("【CBT 阶段指令】当前阶段：").append(mark.stage().name())
                .append("；本轮允许技术：").append(String.join("/", mark.allowedTechniques()));
        if (mark.ageStrategy() == AgeStrategy.BEHAVIORAL_FIRST) {
            sb.append("；年龄约束：低年级不做认知重构，用想法泡泡外化与转移注意替代");
        } else if (mark.ageStrategy() == AgeStrategy.BALANCED) {
            sb.append("；年龄约束：认知重构须用具象化工具（想法天平/侦探找线索）");
        }
        return sb.append("。").toString();
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
