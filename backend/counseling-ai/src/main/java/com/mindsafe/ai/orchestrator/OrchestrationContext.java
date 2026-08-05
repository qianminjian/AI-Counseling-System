package com.mindsafe.ai.orchestrator;

import com.mindsafe.common.enums.RiskLevel;

/**
 * 编排输入上下文（ORCH-001/003/005，design/44 §10.2/§7）
 * <p>
 * 聚合每轮对话的策略输入信号，由调用方（主线 sendMessageStream / 世界B 编排链）构建。
 *
 * @param grade                真实年级（1-6）
 * @param effectiveGrade       含 design/29 动态降级后的语言年级（由调用方计算）
 * @param entryMood            进入心情原始标签（会话级，学生端选择，可为 null）
 * @param currentEmotion       本轮识别的当前情绪（轮级，VCL-001 接入语音情绪后填充，可为 null）
 * @param riskLevel            本轮风险等级（融合后，null 表示绿色无风险）
 * @param profileSignals       画像结构化信号（PROF-025，带置信度；无画像为 null）
 * @param previousEmotionState 上一轮情绪状态（ORCH-003 状态机输入，首轮为 STABLE）
 * @param previousReliefCount  上一轮连续缓解计数（ORCH-003，首轮为 0）
 * @param coldStartNudge       本轮是否触发冷场引导（ORCH-005，design/28 信号并入编排）
 * @param highSensitivity      会话是否处于高敏模式（SAFE-202，命中虐待/丧失/自伤等类别后标记）
 */
public record OrchestrationContext(
        int grade,
        int effectiveGrade,
        String entryMood,
        String currentEmotion,
        RiskLevel riskLevel,
        ProfileSignals profileSignals,
        StrategyProfile.EmotionState previousEmotionState,
        int previousReliefCount,
        boolean coldStartNudge,
        boolean highSensitivity
) {

    /** 向后兼容构造（无状态机输入，首轮默认） */
    public OrchestrationContext(int grade, int effectiveGrade, String entryMood,
                                String currentEmotion, RiskLevel riskLevel,
                                ProfileSignals profileSignals) {
        this(grade, effectiveGrade, entryMood, currentEmotion, riskLevel,
                profileSignals, StrategyProfile.EmotionState.STABLE, 0, false, false);
    }
}
