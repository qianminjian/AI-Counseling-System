package com.mindsafe.ai.orchestrator;

import com.mindsafe.common.enums.RiskLevel;

/**
 * 编排输入上下文（ORCH-001，design/44 §10.2）
 * <p>
 * 聚合每轮对话的策略输入信号，由调用方（主线 sendMessageStream / 世界B 编排链）构建。
 *
 * @param grade           真实年级（1-6）
 * @param effectiveGrade  含 design/29 动态降级后的语言年级（由调用方计算）
 * @param entryMood       进入心情原始标签（会话级，学生端选择：happy/sad/angry/scared/nervous，可为 null）
 * @param currentEmotion  本轮识别的当前情绪（轮级，规范集标签，VCL-001 接入语音情绪后填充，可为 null）
 * @param riskLevel       本轮风险等级（融合后，null 表示绿色无风险）
 * @param profileSignals  画像结构化信号（PROF-022，带置信度；无画像/首次对话为 null）
 */
public record OrchestrationContext(
        int grade,
        int effectiveGrade,
        String entryMood,
        String currentEmotion,
        RiskLevel riskLevel,
        ProfileSignals profileSignals
) {
}
