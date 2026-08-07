package com.mindsafe.service.conversation.strategy;

import com.mindsafe.common.enums.RiskLevel;
import com.mindsafe.ai.safety.CrisisResourceProvider;
import com.mindsafe.ai.safety.CrisisResources;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RiskResponseStrategy 单元测试（DC-010，doing/72 §24）
 * <p>
 * 覆盖：RED 年级适配危机文案 / 安全模式陪伴文案 / 其他场景 null / RED 优先于安全模式。
 */
class RiskResponseStrategyTest {

    private final CrisisResourceProvider provider = mock(CrisisResourceProvider.class);

    @Nested
    @DisplayName("resolveSafetyReply 安全回复策略")
    class ResolveSafetyReply {

        @Test
        @DisplayName("RED → 预审核危机文案（grade 透传 provider，年级适配）")
        void redUsesCrisisReply() {
            when(provider.getRedSafetyReply(4)).thenReturn("危机文案-标准版");

            String reply = RiskResponseStrategy.resolveSafetyReply(RiskLevel.RED, false, 4, provider);

            assertThat(reply).isEqualTo("危机文案-标准版");
            verify(provider).getRedSafetyReply(4);
        }

        @Test
        @DisplayName("安全模式（非本轮 RED）→ 陪伴文案，不调 provider")
        void safetyModeCompanionReply() {
            String reply = RiskResponseStrategy.resolveSafetyReply(null, true, 4, provider);

            assertThat(reply).isEqualTo(CrisisResources.SAFETY_MODE_COMPANION_REPLY);
            verify(provider, never()).getRedSafetyReply(anyInt());
        }

        @Test
        @DisplayName("非 RED 且非安全模式 → null（走正常 LLM 链路）")
        void normalFlowReturnsNull() {
            String reply = RiskResponseStrategy.resolveSafetyReply(RiskLevel.YELLOW, false, 4, provider);

            assertThat(reply).isNull();
            verify(provider, never()).getRedSafetyReply(anyInt());
        }

        @Test
        @DisplayName("RED + 安全模式并存 → RED 文案优先（本轮危机新发生，不可被陪伴话术替代）")
        void redWinsOverSafetyMode() {
            when(provider.getRedSafetyReply(2)).thenReturn("危机文案-短句版");

            String reply = RiskResponseStrategy.resolveSafetyReply(RiskLevel.RED, true, 2, provider);

            assertThat(reply).isEqualTo("危机文案-短句版");
        }

        @Test
        @DisplayName("RED 文案与陪伴文案均为非空（防止空回复静默）")
        void repliesNeverBlank() {
            when(provider.getRedSafetyReply(3)).thenReturn("危机文案");

            assertThat(RiskResponseStrategy.resolveSafetyReply(RiskLevel.RED, false, 3, provider)).isNotBlank();
            assertThat(RiskResponseStrategy.resolveSafetyReply(null, true, 3, provider)).isNotBlank();
        }
    }
}
