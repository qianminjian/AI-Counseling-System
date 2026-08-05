package com.mindsafe.ai.chat;

import com.mindsafe.ai.prompt.PromptTemplateService;
import com.mindsafe.ai.safety.OutputContentFilter;
import com.mindsafe.ai.safety.OutputReviewService;
import com.mindsafe.domain.mapper.ModelCallLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AiChatServiceImpl#buildGenderStyle 单测（PROF-014，矩阵来源 design/29 §3.10）。
 * <p>
 * 性别（male/female/未指定）× 年级段（low=1-2/mid=3-4/high=5-6）9 格全覆盖 +
 * 年级边界（2→low、3→mid、4→mid、5→high）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("性别×年龄交叉沟通风格（PROF-014）")
class AiChatServiceImplGenderStyleTest {

    @Mock private ChatClient.Builder chatClientBuilder;
    @Mock private ChatMemory chatMemory;
    @Mock private OutputContentFilter outputContentFilter;
    @Mock private OutputReviewService outputReviewService;
    @Mock private PromptTemplateService promptTemplateService;
    @Mock private LlmStreamEnhancer llmStreamEnhancer;
    @Mock private ModelCallLogMapper modelCallLogMapper;

    private AiChatServiceImpl service;

    @BeforeEach
    void setUp() {
        when(chatClientBuilder.build()).thenReturn(mock(ChatClient.class));
        service = new AiChatServiceImpl(chatClientBuilder, chatMemory, outputContentFilter,
                outputReviewService, promptTemplateService, llmStreamEnhancer, modelCallLogMapper);
    }

    private String buildGenderStyle(String gender, int grade) {
        return ReflectionTestUtils.invokeMethod(service, "buildGenderStyle", gender, grade);
    }

    @ParameterizedTest(name = "[{index}] gender={0} grade={1} → 含「{2}」")
    @CsvSource({
            // 男生三档
            "male,   1, 男生·低年级",
            "male,   3, 男生·中年级",
            "male,   6, 男生·高年级",
            // 女生三档
            "female, 2, 女生·低年级",
            "female, 4, 女生·中年级",
            "female, 5, 女生·高年级",
    })
    @DisplayName("指定性别 × 年级段 → 9 格矩阵对应风格片段")
    void genderedStyles(String gender, int grade, String expectedHeader) {
        String style = buildGenderStyle(gender, grade);

        assertThat(style).contains(expectedHeader).contains("沟通风格");
    }

    @ParameterizedTest(name = "[{index}] 未指定性别 grade={0} → 含「{1}」")
    @CsvSource({
            "1, 身体感受和颜色",   // low：温和耐心 + 颜色比喻
            "3, 温度计",          // mid：温度计比喻
            "6, 尊重孩子的节奏",  // high：平等探索
    })
    @DisplayName("未指定性别 → 通用风格（仍按年级段区分）")
    void genderNeutralStyles(int grade, String expectedFeature) {
        String style = buildGenderStyle(null, grade);

        assertThat(style).contains("# 沟通风格").contains(expectedFeature);
        assertThat(style).doesNotContain("男生").doesNotContain("女生");
    }

    @Test
    @DisplayName("年级边界：2→low、3→mid、4→mid、5→high")
    void gradeBandBoundaries() {
        assertThat(buildGenderStyle("male", 2)).contains("男生·低年级");
        assertThat(buildGenderStyle("male", 3)).contains("男生·中年级");
        assertThat(buildGenderStyle("male", 4)).contains("男生·中年级");
        assertThat(buildGenderStyle("male", 5)).contains("男生·高年级");
    }

    @Test
    @DisplayName("未知性别字符串（非 male/female）→ 走通用风格")
    void unknownGender_neutral() {
        String style = buildGenderStyle("other", 3);

        assertThat(style).doesNotContain("男生").doesNotContain("女生");
    }

    @Test
    @DisplayName("男生高年级含认知重构要素（证据检验），女生低年级以感受命名为主（设计差异验证）")
    void styleDifferentiation() {
        assertThat(buildGenderStyle("male", 6)).contains("证据");
        assertThat(buildGenderStyle("female", 1)).contains("温柔命名感受");
    }
}
