package com.mindsafe.service.tts;

import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 风险场景语音降级策略（TTSFX-002，design/37 §3.2）
 * <p>
 * 铁律：
 * <ul>
 *   <li>S1/S0 禁用动态合成 → 预合成安抚话术库</li>
 *   <li>S0 后续转热线卡片后不再播放任何语音</li>
 *   <li>合成失败/超时 → 降级链：CosyVoice2 → edge-tts → 纯文字+波波抱抱动画</li>
 *   <li>S2/S3 emotion 强制归入 soothe/calm，语速降至年龄段下限</li>
 * </ul>
 */
@Component
public class VoiceDegradationPolicy {

    /** 允许的安抚情绪集（S2/S3 强制归入） */
    private static final Set<String> SOOTHE_EMOTIONS = Set.of("soothe", "calm");

    /** 语音输出模式 */
    public enum VoiceMode {
        NORMAL,           // 正常动态合成
        SOOTHE_FORCED,    // 强制安抚基调（S2/S3）
        PRE_SYNTHESIZED,  // 预合成话术库（S1）
        SILENT            // 静默（S0 转热线后）
    }

    /** 降级链层级 */
    public enum FallbackTier {
        COSYVOICE2,   // 主合成
        EDGE_TTS,     // 降级
        TEXT_ONLY     // 最终兜底（纯文字+动画）
    }

    /** 语音决策结果 */
    public record VoiceDecision(
            VoiceMode mode,
            String forcedEmotion,  // 非 null 时覆盖原始 emotion
            boolean preSynthesized,
            String reason
    ) {
    }

    /**
     * 根据风险等级决定语音输出模式。
     *
     * @param riskLevel 风险等级（S0/S1/S2/S3，对应 RED/ORANGE/YELLOW/GREEN）
     * @param emotion   原始情绪标签
     * @return 语音决策
     */
    public VoiceDecision decide(String riskLevel, String emotion) {
        return switch (riskLevel) {
            case "S0", "RED" -> new VoiceDecision(
                    VoiceMode.SILENT, null, true,
                    "S0 转热线后不再播放语音，把注意力交给真人求助通道");
            case "S1", "ORANGE" -> new VoiceDecision(
                    VoiceMode.PRE_SYNTHESIZED, "soothe", true,
                    "S1 禁用动态合成，使用预合成安抚话术库（零延迟+零合成事故）");
            case "S2", "YELLOW" -> new VoiceDecision(
                    VoiceMode.SOOTHE_FORCED, forceSoothe(emotion), false,
                    "S2 情绪安抚基调，语速降至年龄段下限");
            default -> new VoiceDecision(
                    VoiceMode.NORMAL, emotion, false,
                    "正常合成");
        };
    }

    /**
     * 合成失败时的降级链。
     *
     * @param currentTier 当前失败层级
     * @return 下一层级（null=无更多降级）
     */
    public FallbackTier nextFallback(FallbackTier currentTier) {
        return switch (currentTier) {
            case COSYVOICE2 -> FallbackTier.EDGE_TTS;
            case EDGE_TTS -> FallbackTier.TEXT_ONLY;
            case TEXT_ONLY -> null;
        };
    }

    /**
     * 判断是否使用预合成音频。
     */
    public boolean usePreSynthesized(VoiceDecision decision) {
        return decision.preSynthesized();
    }

    /**
     * 强制归入安抚情绪（非 soothe/calm 的一律改为 calm）。
     */
    private String forceSoothe(String emotion) {
        if (emotion != null && SOOTHE_EMOTIONS.contains(emotion)) return emotion;
        return "calm";
    }
}
