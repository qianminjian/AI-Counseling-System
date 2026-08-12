package com.mindsafe.ai.ally;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AllianceEnhancer 单元测试（P1-4 板块02：治疗联盟模板行为锁定，对照 TEST-001 覆盖率门禁）。
 * <p>
 * 覆盖：ALLY-201 连续性开场（无历史不生成）/ ALLY-202 收束三段式（巩固-希望-桥接，
 * 亮点/工具提示可选注入）/ ALLY-203 回归照护（缺席阈值分档，禁止压力性质问）。
 */
class AllianceEnhancerTest {

    private final AllianceEnhancer enhancer = new AllianceEnhancer();

    @Nested
    @DisplayName("ALLY-201 连续性开场")
    class ContinuityPrompt {

        @Test
        @DisplayName("无历史（null/空白摘要）→ null，不生成续接")
        void noHistory_returnsNull() {
            assertThat(enhancer.buildContinuityPrompt(null, "小花")).isNull();
            assertThat(enhancer.buildContinuityPrompt("   ", "小花")).isNull();
        }

        @Test
        @DisplayName("有摘要 → 含化名与主题，且提示尊重学生新话题（不强迫续接）")
        void withHistory_mentionsPseudonymAndTopic() {
            String prompt = enhancer.buildContinuityPrompt("和朋友闹矛盾", "小花");

            assertThat(prompt).isNotNull()
                    .contains("小花")
                    .contains("和朋友闹矛盾")
                    .contains("不要生硬复述")
                    .contains("尊重学生今天想聊的新话题");
        }
    }

    @Nested
    @DisplayName("ALLY-202 收束结构化（巩固-希望-桥接）")
    class ClosurePrompt {

        @Test
        @DisplayName("三段式骨架恒在：巩固/希望/桥接")
        void threeSectionSkeleton() {
            String prompt = enhancer.buildClosurePrompt(null, null);

            assertThat(prompt)
                    .contains("1. 巩固")
                    .contains("2. 希望")
                    .contains("3. 桥接")
                    .contains("不要说教");
        }

        @Test
        @DisplayName("亮点注入：非空 highlight 提及，空则不出现")
        void highlightInjectedWhenPresent() {
            assertThat(enhancer.buildClosurePrompt("敢于说出被欺负的事", null))
                    .contains("特别提到：敢于说出被欺负的事");
            assertThat(enhancer.buildClosurePrompt(null, null))
                    .doesNotContain("特别提到");
        }

        @Test
        @DisplayName("工具提示注入：非空 hint 提及，空则不出现")
        void toolHintInjectedWhenPresent() {
            assertThat(enhancer.buildClosurePrompt(null, "深呼吸练习"))
                    .contains("下次可以试试深呼吸练习");
            assertThat(enhancer.buildClosurePrompt(null, null))
                    .doesNotContain("下次可以试试");
        }
    }

    @Nested
    @DisplayName("ALLY-203 中断-回归照护")
    class ReturnCarePrompt {

        @Test
        @DisplayName("缺席 <7 天 → null（无需特殊照护）；needsReturnCare 边界一致")
        void underWeek_noCare() {
            assertThat(enhancer.buildReturnCarePrompt(6, "小花")).isNull();
            assertThat(enhancer.needsReturnCare(6)).isFalse();
            assertThat(enhancer.needsReturnCare(7)).isTrue();
        }

        @Test
        @DisplayName("≥30 天 → 温暖欢迎语气（模板含禁令示例：禁止压力性质问）")
        void monthPlus_warmWelcome() {
            assertThat(enhancer.buildReturnCarePrompt(31, "小花"))
                    .contains("随时可以回来，这里一直等你")
                    .contains("绝对不要说");
        }

        @Test
        @DisplayName("14-29 天 → 轻松问候（提'好久不见'）")
        void twoWeeks_lightGreeting() {
            assertThat(enhancer.buildReturnCarePrompt(15, "小花"))
                    .contains("好久不见")
                    .contains("绝对不要说");
        }

        @Test
        @DisplayName("7-13 天 → 自然续接；所有分档均含禁令示例（不追问原因）")
        void weekToTwoWeeks_naturalResume() {
            String prompt = enhancer.buildReturnCarePrompt(8, "小花");

            assertThat(prompt)
                    .contains("嗨，今天想聊点什么")
                    .contains("绝对不要说")
                    .contains("你是不是不想聊了");
            assertThat(enhancer.buildReturnCarePrompt(30, "小花")).isNotNull();
        }
    }
}
