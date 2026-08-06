package com.mindsafe.service.voice;

import com.mindsafe.ai.risk.EmotionVocabulary;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 语音情绪趋势分析器（VCL-002，design/47 P1）
 * <p>
 * 跨会话语音情绪趋势 + 文本×语音融合与不一致（掩饰）检测。
 * <ul>
 *   <li>趋势判断：近 N 次会话 SER 主导情绪的滑动方向（改善/恶化/平稳）</li>
 *   <li>融合策略：文本情绪 × 语音情绪不一致时标记"掩饰可能"</li>
 *   <li>输出：供教师关注信号（非实时报警，汇入 BL-08 通道）</li>
 * </ul>
 * 纯函数实现。接线时由会话结束异步任务调用。
 */
@Component
public class VoiceEmotionTrendAnalyzer {

    /** 趋势方向 */
    public enum Trend {
        IMPROVING,   // 改善（正面情绪占比上升）
        WORSENING,   // 恶化（负面情绪占比上升）
        STABLE       // 平稳
    }

    /** 文本×语音融合结果 */
    public record FusionResult(
            String dominantEmotion,     // 融合后主导情绪
            boolean inconsistent,       // 文本与语音是否不一致
            String inconsistencyType,   // 不一致类型（MASKING/AMPLIFYING/NONE）
            double confidence           // 融合置信度
    ) {
    }

    /** 趋势分析结果 */
    public record TrendResult(
            Trend trend,
            double negativeRatio,       // 近期负面情绪占比
            double previousNegRatio,    // 前期负面情绪占比
            int sessionCount            // 参与分析的会话数
    ) {
    }

    /**
     * 分析跨会话语音情绪趋势。
     *
     * @param recentEmotions 近 N 次会话的 SER 主导情绪列表（时间正序）
     * @return 趋势结果
     */
    public TrendResult analyzeTrend(List<String> recentEmotions) {
        if (recentEmotions == null || recentEmotions.size() < 3) {
            return new TrendResult(Trend.STABLE, 0, 0, recentEmotions == null ? 0 : recentEmotions.size());
        }

        int mid = recentEmotions.size() / 2;
        List<String> firstHalf = recentEmotions.subList(0, mid);
        List<String> secondHalf = recentEmotions.subList(mid, recentEmotions.size());

        double prevNegRatio = negativeRatio(firstHalf);
        double recentNegRatio = negativeRatio(secondHalf);

        Trend trend;
        if (recentNegRatio > prevNegRatio + 0.2) {
            trend = Trend.WORSENING;
        } else if (prevNegRatio > recentNegRatio + 0.2) {
            trend = Trend.IMPROVING;
        } else {
            trend = Trend.STABLE;
        }

        return new TrendResult(trend, recentNegRatio, prevNegRatio, recentEmotions.size());
    }

    /**
     * 文本×语音情绪融合与不一致检测。
     * <p>
     * 不一致类型：
     * <ul>
     *   <li>MASKING：文本正面但语音负面（可能在掩饰）</li>
     *   <li>AMPLIFYING：文本负面但语音正面（可能在夸大表达）</li>
     *   <li>NONE：一致</li>
     * </ul>
     *
     * @param textEmotion  文本分析情绪（来自 Emotion Agent）
     * @param voiceEmotion 语音 SER 情绪（来自 emotion2vec）
     * @return 融合结果
     */
    public FusionResult fuse(String textEmotion, String voiceEmotion) {
        if (textEmotion == null || voiceEmotion == null) {
            String dominant = textEmotion != null ? textEmotion : voiceEmotion;
            return new FusionResult(dominant != null ? dominant : "neutral", false, "NONE", 0.5);
        }

        boolean textNeg = isNegative(textEmotion);
        boolean voiceNeg = isNegative(voiceEmotion);

        if (textNeg == voiceNeg) {
            // 一致：取语音（更接近真实情感状态）
            return new FusionResult(voiceEmotion, false, "NONE", 0.85);
        }

        if (!textNeg && voiceNeg) {
            // 文本正面但语音负面 → 掩饰可能
            return new FusionResult(voiceEmotion, true, "MASKING", 0.7);
        }

        // 文本负面但语音正面 → 夸大表达
        return new FusionResult(textEmotion, true, "AMPLIFYING", 0.6);
    }

    /**
     * 判断趋势是否应触发教师关注信号（非实时报警）。
     */
    public boolean shouldNotifyTeacher(TrendResult result) {
        return result.trend() == Trend.WORSENING && result.sessionCount() >= 4;
    }

    private boolean isNegative(String emotion) {
        // ARCH-003：内嵌 NEGATIVE_EMOTIONS → EmotionVocabulary 统一判定（anxious/crisis 等全管线一致）
        return EmotionVocabulary.isNegative(emotion);
    }

    private double negativeRatio(List<String> emotions) {
        if (emotions.isEmpty()) return 0;
        long negCount = emotions.stream().filter(this::isNegative).count();
        return (double) negCount / emotions.size();
    }
}
