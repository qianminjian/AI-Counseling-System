package com.mindsafe.ai.prompt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PromptTemplateService 单测（模板加载/变量渲染/年级路由）。
 */
@DisplayName("Prompt 模板服务")
class PromptTemplateServiceTest {

    private final PromptTemplateService service = new PromptTemplateService();

    @Nested
    @DisplayName("render 变量渲染")
    class Render {

        @Test
        @DisplayName("双花括号变量被替换")
        void replacesVariables() {
            String result = service.render("prompts/test_template.md",
                    Map.of("name", "小明", "grade", "3"));

            assertThat(result).isEqualTo("你好 小明，你是 3 年级的学生。");
        }

        @Test
        @DisplayName("null 变量值 → 替换为空串")
        void nullValue_replacedWithEmpty() {
            String result = service.render("prompts/test_template.md",
                    java.util.Collections.singletonMap("name", null));

            assertThat(result).contains("你好 ，").doesNotContain("{{name}}");
        }

        @Test
        @DisplayName("未提供的变量保留占位符原文")
        void missingVariable_keptAsIs() {
            String result = service.render("prompts/test_template.md", Map.of());

            assertThat(result).contains("{{name}}").contains("{{grade}}");
        }
    }

    @Nested
    @DisplayName("getTemplate 加载与异常")
    class Load {

        @Test
        @DisplayName("classpath 模板可加载且缓存一致")
        void loadsAndCaches() {
            String first = service.getTemplate("prompts/test_template.md");
            String second = service.getTemplate("prompts/test_template.md");

            assertThat(first).contains("{{name}}");
            assertThat(second).isSameAs(first);
        }

        @Test
        @DisplayName("模板不存在 → IllegalStateException")
        void missingTemplate_throws() {
            assertThatThrownBy(() -> service.getTemplate("prompts/not_exist.md"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not_exist.md");
        }
    }

    @Nested
    @DisplayName("languageTemplateForGrade 年级路由（design/29 三档）")
    class GradeRouting {

        @Test
        @DisplayName("1-2 年级 → LANG_001，3-4 年级 → LANG_002，5-6 年级 → LANG_003")
        void gradeBands() {
            assertThat(PromptTemplateService.languageTemplateForGrade(1)).isEqualTo(PromptTemplateService.LANG_001);
            assertThat(PromptTemplateService.languageTemplateForGrade(2)).isEqualTo(PromptTemplateService.LANG_001);
            assertThat(PromptTemplateService.languageTemplateForGrade(3)).isEqualTo(PromptTemplateService.LANG_002);
            assertThat(PromptTemplateService.languageTemplateForGrade(4)).isEqualTo(PromptTemplateService.LANG_002);
            assertThat(PromptTemplateService.languageTemplateForGrade(5)).isEqualTo(PromptTemplateService.LANG_003);
            assertThat(PromptTemplateService.languageTemplateForGrade(6)).isEqualTo(PromptTemplateService.LANG_003);
        }
    }
}
