package com.mindsafe.service.conversation.strategy;

import com.mindsafe.common.enums.RiskLevel;
import com.mindsafe.ai.safety.CrisisHotlineProvider;
import com.mindsafe.ai.safety.CrisisResources;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RiskResponseStrategy 单元测试（DC-010，doing/72 §24）
 * <p>
 * 覆盖：RED 年级适配危机文案 / 安全模式陪伴文案 / 其他场景 null / RED 优先于安全模式。
 * <p>
 * P1-2 板块02：CrisisResourceProvider 已删除，年级模板选择内联本类，
 * 热线渲染收敛为 CrisisHotlineProvider.render（mock 类型随之切换）。
 */
class RiskResponseStrategyTest {

    private final CrisisHotlineProvider provider = mock(CrisisHotlineProvider.class);

    @Nested
    @DisplayName("resolveSafetyReply 安全回复策略")
    class ResolveSafetyReply {

        @Test
        @DisplayName("RED → 预审核危机文案（grade≤2 短句版，否则标准版，经 render 渲染）")
        void redUsesCrisisReply() {
            when(provider.render(CrisisResources.RED_SAFETY_REPLY)).thenReturn("危机文案-标准版");

            String reply = RiskResponseStrategy.resolveSafetyReply(RiskLevel.RED, false, 4, provider);

            assertThat(reply).isEqualTo("危机文案-标准版");
            verify(provider).render(CrisisResources.RED_SAFETY_REPLY);
        }

        @Test
        @DisplayName("安全模式（非本轮 RED）→ 陪伴文案，不调 render")
        void safetyModeCompanionReply() {
            String reply = RiskResponseStrategy.resolveSafetyReply(null, true, 4, provider);

            assertThat(reply).isEqualTo(CrisisResources.SAFETY_MODE_COMPANION_REPLY);
            verify(provider, never()).render(anyString());
        }

        @Test
        @DisplayName("非 RED 且非安全模式 → null（走正常 LLM 链路）")
        void normalFlowReturnsNull() {
            String reply = RiskResponseStrategy.resolveSafetyReply(RiskLevel.YELLOW, false, 4, provider);

            assertThat(reply).isNull();
            verify(provider, never()).render(anyString());
        }

        @Test
        @DisplayName("RED + 安全模式并存 → RED 文案优先（本轮危机新发生，不可被陪伴话术替代）")
        void redWinsOverSafetyMode() {
            when(provider.render(CrisisResources.RED_SAFETY_REPLY_LOWER_GRADE)).thenReturn("危机文案-短句版");

            String reply = RiskResponseStrategy.resolveSafetyReply(RiskLevel.RED, true, 2, provider);

            assertThat(reply).isEqualTo("危机文案-短句版");
        }

        @Test
        @DisplayName("RED 文案与陪伴文案均为非空（防止空回复静默）")
        void repliesNeverBlank() {
            when(provider.render(CrisisResources.RED_SAFETY_REPLY)).thenReturn("危机文案");

            assertThat(RiskResponseStrategy.resolveSafetyReply(RiskLevel.RED, false, 3, provider)).isNotBlank();
            assertThat(RiskResponseStrategy.resolveSafetyReply(null, true, 3, provider)).isNotBlank();
        }
    }

    @Nested
    @DisplayName("buildTimeLimitGuidance 时长超限引导语（AUTH-030）")
    class TimeLimitGuidance {

        @Test
        @DisplayName("引导语含调用方传入的热线号码（DOC-073 B1：不再内嵌硬编码 12355）")
        void guidanceUsesPassedHotline() {
            String guidance = RiskResponseStrategy.buildTimeLimitGuidance("0571-12345678");

            assertThat(guidance).contains("0571-12345678")
                    .doesNotContain("12355")
                    .doesNotContain("400-161-9995");
        }

        @Test
        @DisplayName("引导语始终非空（防止空回复静默）")
        void guidanceNeverBlank() {
            assertThat(RiskResponseStrategy.buildTimeLimitGuidance("0571-12345678")).isNotBlank();
        }
    }
}
