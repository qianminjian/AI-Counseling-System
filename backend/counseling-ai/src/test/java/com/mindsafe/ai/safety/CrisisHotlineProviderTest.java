package com.mindsafe.ai.safety;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CrisisHotlineProvider 契约测试（DOC-073 B1，doing/77 §22）
 * <p>
 * 锁死全链：改配置 → RED 硬短路 / Layer2 召回 / Layer1 安全模板
 * 三路径输出全部为新号码（P1-2 板块02：CrisisResourceProvider 已删除，
 * 热线显示文本与紧急联系方式等死方法不再存在，渲染统一收敛至 render）；
 * 默认配置回退兜底常量。
 */
class CrisisHotlineProviderTest {

    private static final String CUSTOM_HOTLINE = "0571-12345678";

    @Nested
    @DisplayName("Provider 本体")
    class ProviderCore {

        @Test
        @DisplayName("缺省路径回退兜底常量（失败安全，不允许 fail-fast）")
        void defaultFallsBackToConstant() {
            assertThat(new CrisisHotlineProvider().hotline())
                    .isEqualTo(CrisisResources.NATIONAL_PSYCHOLOGICAL_AID);
        }

        @Test
        @DisplayName("配置注入自定义号码生效")
        void configuredHotlineApplied() {
            assertThat(new CrisisHotlineProvider(CUSTOM_HOTLINE).hotline()).isEqualTo(CUSTOM_HOTLINE);
        }

        @Test
        @DisplayName("render 替换 {hotline} 占位符")
        void renderReplacesPlaceholder() {
            assertThat(new CrisisHotlineProvider(CUSTOM_HOTLINE).render("请拨打 {hotline}（24 小时）"))
                    .isEqualTo("请拨打 " + CUSTOM_HOTLINE + "（24 小时）");
        }

        @Test
        @DisplayName("render 对无占位符模板原样返回（1-2 年级短句版安全）")
        void renderPassesThroughTemplateWithoutPlaceholder() {
            String lowerGrade = CrisisResources.RED_SAFETY_REPLY_LOWER_GRADE;
            assertThat(new CrisisHotlineProvider(CUSTOM_HOTLINE).render(lowerGrade)).isEqualTo(lowerGrade);
        }

        @Test
        @DisplayName("占位符常量与模板占位符一致（模板契约不被静默改写）")
        void placeholderContractStable() {
            assertThat(CrisisHotlineProvider.PLACEHOLDER).isEqualTo("{hotline}");
            assertThat(CrisisResources.RED_SAFETY_REPLY).contains(CrisisHotlineProvider.PLACEHOLDER);
            assertThat(CrisisResources.L5_SAFETY_REPLY).contains(CrisisHotlineProvider.PLACEHOLDER);
            assertThat(RecallPhrases.ESCALATE_RECALL).contains(CrisisHotlineProvider.PLACEHOLDER);
        }

        @Test
        @DisplayName("L5 预审核模板可经 render 渲染且无占位符残留（防呆：禁止直出）")
        void l5TemplateRenderableWithoutResidue() {
            String rendered = new CrisisHotlineProvider(CUSTOM_HOTLINE).render(CrisisResources.L5_SAFETY_REPLY);

            assertThat(rendered).contains(CUSTOM_HOTLINE)
                    .doesNotContain(CrisisHotlineProvider.PLACEHOLDER);
        }
    }

    @Nested
    @DisplayName("全链契约：改配置 → 四路径输出均为新号码")
    class ChainContract {

        @Test
        @DisplayName("RED 硬短路话术经配置渲染，不含默认号码（年级模板选择已内联 RiskResponseStrategy）")
        void redReplyRenderedFromConfig() {
            CrisisHotlineProvider provider = new CrisisHotlineProvider(CUSTOM_HOTLINE);

            String redReply = provider.render(CrisisResources.RED_SAFETY_REPLY);

            assertThat(redReply).contains(CUSTOM_HOTLINE)
                    .doesNotContain(CrisisResources.NATIONAL_PSYCHOLOGICAL_AID)
                    .doesNotContain("12355");
        }

        @Test
        @DisplayName("Layer2 召回话术（escalate 档）经配置渲染")
        void recallRenderedFromConfig() {
            String recall = new CrisisHotlineProvider(CUSTOM_HOTLINE).render(RecallPhrases.ESCALATE_RECALL);

            assertThat(recall).contains(CUSTOM_HOTLINE)
                    .doesNotContain(CrisisResources.NATIONAL_PSYCHOLOGICAL_AID);
        }

        @Test
        @DisplayName("Layer1 安全模板经配置渲染（OutputContentFilter 主路径）")
        void layer1TemplateRenderedFromConfig() {
            OutputContentFilter filter = new OutputContentFilter(
                    new SafetyKeywordLibrary(new com.fasterxml.jackson.databind.ObjectMapper()),
                    org.mockito.Mockito.mock(OutputSafetyReporter.class),
                    new CrisisHotlineProvider(CUSTOM_HOTLINE));

            String template = filter.safeTemplate(
                    new SafetyKeywordLibrary.KeywordHit("self_harm_method", "自伤/伤人方法", "怎么割腕", "block"));

            assertThat(template).contains(CUSTOM_HOTLINE)
                    .doesNotContain(CrisisResources.NATIONAL_PSYCHOLOGICAL_AID);
        }

        @Test
        @DisplayName("默认配置下三路径输出与兜底号码一致（回归基线）")
        void defaultChainMatchesFallback() {
            CrisisHotlineProvider provider = new CrisisHotlineProvider();

            assertThat(provider.render(CrisisResources.RED_SAFETY_REPLY)).contains(CrisisResources.NATIONAL_PSYCHOLOGICAL_AID);
            assertThat(provider.render(RecallPhrases.ESCALATE_RECALL)).contains(CrisisResources.NATIONAL_PSYCHOLOGICAL_AID);
        }
    }
}
