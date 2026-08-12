package com.mindsafe.ai.safety;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RecallPhrases 单元测试（P1-4 板块02：Layer2 召回替换话术合规凭据锁定，SAFE-202）。
 * <p>
 * block 档：撤回不当表述、不做诊断/不承诺保密（不涉及热线号码）；
 * escalate 档：安全处置话术，热线经 {hotline} 占位符渲染（铁律同 CrisisResources）。
 */
class RecallPhrasesTest {

    @Nested
    @DisplayName("BLOCK_RECALL（撤回不当表述）")
    class BlockRecall {

        @Test
        @DisplayName("回到陪伴定位：不做诊断、不承诺保密、危险时告知大人")
        void boundaryPromises() {
            assertThat(RecallPhrases.BLOCK_RECALL)
                    .contains("我不是医生，不能给你下结论")
                    .contains("老师一般看不到")
                    .contains("告诉能保护你的大人");
        }

        @Test
        @DisplayName("block 档不涉及热线：无占位符、无热线号码明文")
        void noHotlineInBlock() {
            assertThat(RecallPhrases.BLOCK_RECALL)
                    .doesNotContain("{")
                    .doesNotContain(CrisisResources.NATIONAL_PSYCHOLOGICAL_AID)
                    .doesNotContain("热线");
        }
    }

    @Nested
    @DisplayName("ESCALATE_RECALL（高风险安全处置）")
    class EscalateRecall {

        @Test
        @DisplayName("含热线占位符（由 CrisisHotlineProvider 渲染，禁止直拼号码）")
        void hotlinePlaceholderContract() {
            assertThat(RecallPhrases.ESCALATE_RECALL)
                    .contains("全国心理援助热线")
                    .contains(CrisisHotlineProvider.PLACEHOLDER);
        }

        @Test
        @DisplayName("渲染后无占位符残留，号码为生效配置")
        void renderedWithoutResidue() {
            String rendered = new CrisisHotlineProvider("0571-12345678").render(RecallPhrases.ESCALATE_RECALL);

            assertThat(rendered).contains("0571-12345678").doesNotContain("{");
        }

        @Test
        @DisplayName("话术保留安全处置核心：告知老师、引导身边大人、不一个人扛")
        void safetyHandlingCore() {
            assertThat(RecallPhrases.ESCALATE_RECALL)
                    .contains("告诉能保护你的老师")
                    .contains("信得过的大人")
                    .contains("不用一个人扛");
        }
    }
}
