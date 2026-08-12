package com.mindsafe.service.risk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SLA 阈值权威常量源（P1-4）：
 * 映射完整（3→15/2→60/1→480/0→1440）+ null/未知等级回落 GREEN 口径。
 */
class RiskSlaConstantsTest {

    @Test
    @DisplayName("SLA 映射完整：RED 15min / ORANGE 60min / YELLOW 480min / GREEN 1440min")
    void mappingComplete() {
        assertThat(RiskSlaConstants.SLA_DISPOSE_MINUTES).containsExactlyInAnyOrderEntriesOf(
                java.util.Map.of(3, 15L, 2, 60L, 1, 480L, 0, 1440L));
    }

    @Test
    @DisplayName("slaMinutesFor：null/未知等级回落 1440min（GREEN 口径）")
    void nullAndUnknownFallbackToDefault() {
        assertThat(RiskSlaConstants.slaMinutesFor(null)).isEqualTo(1440L);
        assertThat(RiskSlaConstants.slaMinutesFor(9)).isEqualTo(RiskSlaConstants.DEFAULT_SLA_MINUTES);
    }

    @Test
    @DisplayName("slaMinutesFor：四等级精确命中")
    void levelMappingExact() {
        assertThat(RiskSlaConstants.slaMinutesFor(3)).isEqualTo(15L);
        assertThat(RiskSlaConstants.slaMinutesFor(2)).isEqualTo(60L);
        assertThat(RiskSlaConstants.slaMinutesFor(1)).isEqualTo(480L);
        assertThat(RiskSlaConstants.slaMinutesFor(0)).isEqualTo(1440L);
    }
}
