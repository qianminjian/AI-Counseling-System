package com.mindsafe.ai.voice;

import java.util.List;

/**
 * 语音分析结果（ASR + 情感识别）
 */
public record VoiceAnalysisResult(
        String text,
        EmotionInfo emotion,
        double durationSeconds
) {
    public record EmotionInfo(
            String label,       // 中文标签
            String labelEn,     // 英文标签
            double confidence,  // 置信度 0~1
            List<Double> scores // 9 类得分
    ) {}

    /** 情绪是否有效（置信度 > 阈值且非 unknown/other） */
    public boolean hasValidEmotion() {
        return emotion != null
                && emotion.confidence() > 0.6
                && !"unknown".equals(emotion.labelEn())
                && !"other".equals(emotion.labelEn());
    }

    /** 是否为消极情绪（sad / fearful / angry） */
    public boolean isNegativeEmotion() {
        if (!hasValidEmotion()) return false;
        return switch (emotion.labelEn()) {
            case "sad", "fearful", "angry", "disgusted" -> true;
            default -> false;
        };
    }

    /** 消极情绪对应的风险辅助等级 */
    public int emotionRiskLevel() {
        if (!hasValidEmotion()) return 0;
        return switch (emotion.labelEn()) {
            case "fearful" -> 2;  // 恐惧 → 橙色辅助
            case "sad" -> 2;      // 悲伤 → 橙色辅助
            case "angry" -> 1;    // 愤怒 → 黄色辅助
            case "disgusted" -> 1; // 厌恶 → 黄色辅助
            default -> 0;
        };
    }
}
