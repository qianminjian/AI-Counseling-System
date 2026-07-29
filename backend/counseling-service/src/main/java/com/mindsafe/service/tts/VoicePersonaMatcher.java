package com.mindsafe.service.tts;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 音色画像匹配器（TMATCH-002，design/48 P1）
 * <p>
 * 画像匹配微调 + 安全/危机稳定基调锁定 + 预合成矩阵。
 * <ul>
 *   <li>冷启动默认匹配：性别认同 × 年龄段 → 默认音色</li>
 *   <li>emotionState → prosody 基调联动（非仅 instruct）</li>
 *   <li>安全/危机：锁定稳定基调，不允许活泼/俏皮音色</li>
 *   <li>预合成矩阵：情绪×场景 → 预合成话术 ID（统一 TTSFX-002）</li>
 * </ul>
 * 纯规则实现。接线时由 TTS 调用链消费。
 */
@Component
public class VoicePersonaMatcher {

    /** 音色 ID */
    public static final String VOICE_GENTLE_FEMALE = "zh-CN-XiaoxiaoNeural";   // 温柔女声
    public static final String VOICE_WARM_MALE = "zh-CN-YunxiNeural";          // 温暖男声
    public static final String VOICE_NEUTRAL = "zh-CN-XiaoyiNeural";           // 中性角色
    public static final String VOICE_STABLE = "zh-CN-YunyangNeural";           // 稳定基调（危机用）

    /** prosody 基调 */
    public enum Prosody {
        GENTLE("gentle", "+0%", "-10%"),     // 温柔：语速正常，音调略低
        WARM("warm", "-5%", "+0%"),          // 温暖：语速略慢
        CALM("calm", "-15%", "-5%"),         // 平静：语速慢，音调低
        STABLE("stable", "-10%", "-10%"),    // 稳定：危机锁定
        NEUTRAL("neutral", "+0%", "+0%");    // 中性：默认

        private final String style;
        private final String rate;
        private final String pitch;

        Prosody(String style, String rate, String pitch) {
            this.style = style;
            this.rate = rate;
            this.pitch = pitch;
        }

        public String style() { return style; }
        public String rate() { return rate; }
        public String pitch() { return pitch; }
    }

    /** 匹配结果 */
    public record MatchResult(
            String voiceId,
            Prosody prosody,
            boolean locked,       // 是否被安全锁定（不可覆盖）
            String preSynthId     // 预合成话术 ID（null=实时合成）
    ) {
    }

    /** 预合成矩阵：情绪×场景 → 话术 ID */
    private static final Map<String, String> PRE_SYNTH_MATRIX = Map.of(
            "crisis:grounding", "PS-CRISIS-GROUND-001",
            "crisis:safety", "PS-CRISIS-SAFETY-001",
            "crisis:hotline", "PS-CRISIS-HOTLINE-001",
            "anxious:breathing", "PS-ANX-BREATH-001",
            "anxious:grounding", "PS-ANX-GROUND-001",
            "sad:comfort", "PS-SAD-COMFORT-001",
            "angry:validate", "PS-ANG-VALIDATE-001"
    );

    /**
     * 匹配音色 + prosody。
     *
     * @param genderIdentity 性别认同（male/female/neutral，可为 null）
     * @param grade          年级（1-6）
     * @param emotionState   情绪状态（STABLE/ACTIVATED/CRISIS）
     * @param riskLevel      风险等级（S0/S1/S2/S3，可为 null）
     * @return 匹配结果
     */
    public MatchResult match(String genderIdentity, int grade, String emotionState, String riskLevel) {
        // 安全锁定：S0/S1 或 CRISIS → 稳定基调，不可覆盖
        boolean safetyLocked = "CRISIS".equals(emotionState)
                || "S0".equals(riskLevel) || "S1".equals(riskLevel)
                || "RED".equals(riskLevel);

        if (safetyLocked) {
            return new MatchResult(VOICE_STABLE, Prosody.STABLE, true, null);
        }

        // 冷启动默认匹配：性别 × 年龄
        String voiceId = resolveVoice(genderIdentity, grade);

        // emotionState → prosody 联动
        Prosody prosody = resolveProsody(emotionState);

        return new MatchResult(voiceId, prosody, false, null);
    }

    /**
     * 查询预合成话术 ID。
     *
     * @param emotion 情绪标签
     * @param scene   场景标签（grounding/breathing/safety/hotline/comfort/validate）
     * @return 预合成 ID，null=无预合成需实时合成
     */
    public String lookupPreSynth(String emotion, String scene) {
        if (emotion == null || scene == null) return null;
        return PRE_SYNTH_MATRIX.get(emotion.toLowerCase() + ":" + scene.toLowerCase());
    }

    /**
     * 获取所有预合成话术 ID 列表（供预合成引擎批量生成）。
     */
    public List<String> allPreSynthIds() {
        return List.copyOf(PRE_SYNTH_MATRIX.values());
    }

    private String resolveVoice(String genderIdentity, int grade) {
        if (genderIdentity == null) return VOICE_NEUTRAL;
        return switch (genderIdentity.toLowerCase()) {
            case "female" -> VOICE_GENTLE_FEMALE;
            case "male" -> VOICE_WARM_MALE;
            default -> VOICE_NEUTRAL;
        };
    }

    private Prosody resolveProsody(String emotionState) {
        if (emotionState == null) return Prosody.NEUTRAL;
        return switch (emotionState) {
            case "ACTIVATED" -> Prosody.CALM;
            case "CRISIS" -> Prosody.STABLE;
            default -> Prosody.GENTLE;
        };
    }
}
