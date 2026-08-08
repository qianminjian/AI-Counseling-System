package com.mindsafe.service.tts;

import com.mindsafe.service.tts.VoiceDegradationPolicy.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TTSFX-002 风险语音降级策略 单元测试
 */
class VoiceDegradationPolicyTest {

    private final VoiceDegradationPolicy policy = new VoiceDegradationPolicy();

    @Test
    @DisplayName("S3/GREEN → NORMAL，保留原始 emotion")
    void green_normal() {
        VoiceDecision d = policy.decide("S3", "happy");
        assertThat(d.mode()).isEqualTo(VoiceMode.NORMAL);
        assertThat(d.forcedEmotion()).isEqualTo("happy");
        assertThat(d.preSynthesized()).isFalse();
    }

    @Test
    @DisplayName("S2/YELLOW → SOOTHE_FORCED，非安抚情绪强制 calm")
    void yellow_sootheForced() {
        VoiceDecision d = policy.decide("S2", "happy");
        assertThat(d.mode()).isEqualTo(VoiceMode.SOOTHE_FORCED);
        assertThat(d.forcedEmotion()).isEqualTo("calm");

        // 已是 soothe 则保留
        VoiceDecision d2 = policy.decide("YELLOW", "soothe");
        assertThat(d2.forcedEmotion()).isEqualTo("soothe");
    }

    @Test
    @DisplayName("S1/ORANGE → PRE_SYNTHESIZED，预合成")
    void orange_preSynthesized() {
        VoiceDecision d = policy.decide("S1", "happy");
        assertThat(d.mode()).isEqualTo(VoiceMode.PRE_SYNTHESIZED);
        assertThat(d.preSynthesized()).isTrue();
        assertThat(d.forcedEmotion()).isEqualTo("soothe");
    }

    @Test
    @DisplayName("S0/RED → SILENT，不再播放语音")
    void red_silent() {
        VoiceDecision d = policy.decide("S0", "happy");
        assertThat(d.mode()).isEqualTo(VoiceMode.SILENT);
        assertThat(d.preSynthesized()).isTrue();
    }
}
