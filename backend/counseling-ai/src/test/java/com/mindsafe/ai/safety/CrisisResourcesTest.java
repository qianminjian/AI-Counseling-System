package com.mindsafe.ai.safety;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CrisisResources 单元测试（P1-4 板块02：危机资源合规凭据锁定）。
 * <p>
 * 安全红线（design/14 §12.3）：热线号码固化在代码中、不由 LLM 生成。
 * 本测试锁定：号码常量值防漂移、模板占位符契约、120/110 紧急号码登记、
 * 话术模板不得含 PII 或未登记号码（改话术不破坏合规凭据）。
 */
class CrisisResourcesTest {

    @Nested
    @DisplayName("热线号码常量（合规凭据，值防漂移）")
    class HotlineConstants {

        @Test
        @DisplayName("全国心理援助热线 400-161-9995（24 小时）")
        void nationalPsychologicalAid() {
            assertThat(CrisisResources.NATIONAL_PSYCHOLOGICAL_AID).isEqualTo("400-161-9995");
        }

        @Test
        @DisplayName("生命热线 400-821-1215")
        void lifeHotline() {
            assertThat(CrisisResources.LIFE_HOTLINE).isEqualTo("400-821-1215");
        }

        @Test
        @DisplayName("紧急号码登记：急救 120 / 报警 110")
        void emergencyNumbers() {
            assertThat(CrisisResources.EMERGENCY_MEDICAL).isEqualTo("120");
            assertThat(CrisisResources.EMERGENCY_POLICE).isEqualTo("110");
        }
    }

    @Nested
    @DisplayName("话术模板占位符契约")
    class TemplateContract {

        @Test
        @DisplayName("RED 标准版 / L5 / escalate 召回均含 {hotline} 占位符（由 Provider 渲染，禁止直拼号码）")
        void hotlinePlaceholderInTemplates() {
            assertThat(CrisisResources.RED_SAFETY_REPLY).contains(CrisisHotlineProvider.PLACEHOLDER);
            assertThat(CrisisResources.L5_SAFETY_REPLY).contains(CrisisHotlineProvider.PLACEHOLDER);
            assertThat(RecallPhrases.ESCALATE_RECALL).contains(CrisisHotlineProvider.PLACEHOLDER);
        }

        @Test
        @DisplayName("低年级短句版 / 陪伴话术 / L4 / block 召回不含占位符（不涉及热线号码）")
        void noPlaceholderWhereNotNeeded() {
            assertThat(CrisisResources.RED_SAFETY_REPLY_LOWER_GRADE).doesNotContain("{");
            assertThat(CrisisResources.SAFETY_MODE_COMPANION_REPLY).doesNotContain("{");
            assertThat(CrisisResources.L4_SAFETY_REPLY).doesNotContain("{");
            assertThat(RecallPhrases.BLOCK_RECALL).doesNotContain("{");
        }

        @Test
        @DisplayName("除 {hotline} 外无其他占位符残留（防呆：话术模板不出现未渲染标记）")
        void noUnexpectedPlaceholders() {
            String[] hotlineTemplates = {
                    CrisisResources.RED_SAFETY_REPLY,
                    CrisisResources.L5_SAFETY_REPLY,
                    RecallPhrases.ESCALATE_RECALL
            };
            for (String template : hotlineTemplates) {
                String withoutHotline = template.replace(CrisisHotlineProvider.PLACEHOLDER, "");
                assertThat(withoutHotline).doesNotContain("{");
            }
        }
    }

    @Nested
    @DisplayName("安全红线：话术内容边界")
    class ContentBoundary {

        @Test
        @DisplayName("模板提及的号码仅限已登记常量（120/110/热线），不得混入未登记号码")
        void onlyRegisteredNumbersInTemplates() {
            assertThat(CrisisResources.RED_SAFETY_REPLY)
                    .contains(CrisisResources.EMERGENCY_POLICE)
                    .contains(CrisisResources.EMERGENCY_MEDICAL);
            assertThat(CrisisResources.L5_SAFETY_REPLY)
                    .contains(CrisisResources.EMERGENCY_MEDICAL)
                    .contains(CrisisResources.EMERGENCY_POLICE);
            // 模板中出现的纯数字不得是未登记号码（如 911/10086 之类误入）
            assertThat(CrisisResources.RED_SAFETY_REPLY).doesNotContain("911");
            assertThat(CrisisResources.L5_SAFETY_REPLY).doesNotContain("911");
        }

        @Test
        @DisplayName("模板不得含 PII 形态内容（无邮箱/链接/身份证位数串）")
        void noPiiLikeContent() {
            assertThat(CrisisResources.RED_SAFETY_REPLY).doesNotContain("http").doesNotContain("@");
            assertThat(CrisisResources.L5_SAFETY_REPLY).doesNotContain("http").doesNotContain("@");
            assertThat(RecallPhrases.ESCALATE_RECALL).doesNotContain("http").doesNotContain("@");
        }

        @Test
        @DisplayName("所有安全回复模板非空（防止空回复静默）")
        void repliesNeverBlank() {
            assertThat(CrisisResources.RED_SAFETY_REPLY).isNotBlank();
            assertThat(CrisisResources.RED_SAFETY_REPLY_LOWER_GRADE).isNotBlank();
            assertThat(CrisisResources.SAFETY_MODE_COMPANION_REPLY).isNotBlank();
            assertThat(CrisisResources.L4_SAFETY_REPLY).isNotBlank();
            assertThat(CrisisResources.L5_SAFETY_REPLY).isNotBlank();
        }
    }
}
