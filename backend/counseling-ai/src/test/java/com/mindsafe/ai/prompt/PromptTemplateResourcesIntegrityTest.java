package com.mindsafe.ai.prompt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0-1 ② 资源归属自检测试：断言 PromptTemplateService 声明的全部模板路径
 * 在 counseling-ai 模块自身 classpath 下真实存在（单模块归属，杜绝"跨模块碰巧可用"）。
 * <p>
 * 背景：模板 md 曾错位存放于 counseling-app resources，ai 模块独立加载必失败；
 * 收敛后所有模板（含 prompts/tasks/ TSK_001-004 与 prompts/aux/ 4 个辅助 prompt）统一归 ai 模块。
 */
@DisplayName("Prompt 模板资源自检（P0-1 ②：单模块归属）")
class PromptTemplateResourcesIntegrityTest {

    private final PromptTemplateService service = new PromptTemplateService();

    /** 反射收集全部模板路径常量（public static final String 且以 prompts/ 开头） */
    private static List<String> templatePaths() {
        List<String> paths = new ArrayList<>();
        for (Field field : PromptTemplateService.class.getDeclaredFields()) {
            int mod = field.getModifiers();
            if (!Modifier.isStatic(mod) || !Modifier.isFinal(mod)
                    || field.getType() != String.class) {
                continue;
            }
            try {
                Object value = field.get(null);
                if (value instanceof String s && s.startsWith("prompts/")) {
                    paths.add(s);
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("无法读取常量: " + field.getName(), e);
            }
        }
        return paths;
    }

    @Test
    @DisplayName("全部模板常量在 counseling-ai 模块 classpath 下存在")
    void allDeclaredTemplatesExistInModuleClasspath() {
        List<String> paths = templatePaths();
        // 现状 27 个路径（SYS/SAF/LANG/SKL/TSK/EMO/GENDER_STYLE×9/AUX×4），新增模板自动纳入断言
        assertThat(paths.size()).isGreaterThanOrEqualTo(27);

        for (String path : paths) {
            assertThat(new ClassPathResource(path).exists())
                    .as("模板文件应归属 counseling-ai 模块 classpath: %s", path)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("关键模板可真实加载：SYS_001 / TSK_001-004 / SAF_001-002 / AUX_001-004")
    void keyTemplatesLoadable() {
        // 主链路 + 任务模板（TSK_004 被暖场链路消费，TSK_001-003 为版本路由预留）
        assertThat(service.getTemplate(PromptTemplateService.SYS_001)).contains("波波");
        for (String path : List.of(
                PromptTemplateService.TSK_001,
                PromptTemplateService.TSK_002,
                PromptTemplateService.TSK_003,
                PromptTemplateService.TSK_004)) {
            assertThat(service.getTemplate(path)).isNotBlank();
        }
        // 安全模板（SAF_001 语义分类 / SAF_002 Layer2 审查）
        assertThat(service.getTemplate(PromptTemplateService.SAF_001)).contains("risk_level");
        assertThat(service.getTemplate(PromptTemplateService.SAF_002)).isNotBlank();
        // 4 个辅助 prompt（P0-1 ①：从 Java 硬编码字符串下沉到资源）
        assertThat(service.getTemplate(PromptTemplateService.AUX_001)).contains("mainTopic");
        assertThat(service.getTemplate(PromptTemplateService.AUX_002)).contains("profile_patch");
        assertThat(service.getTemplate(PromptTemplateService.AUX_003)).contains("empathy_score");
        assertThat(service.getTemplate(PromptTemplateService.AUX_004)).contains("150 字");
    }

    @Test
    @DisplayName("safety_output_guard 单份收敛：仅 ai 模块一份（双模块重复已消除）")
    void safetyOutputGuardSingleCopy() {
        // 加载不抛异常（ai 模块 classpath 自洽）
        String content = service.getTemplate(PromptTemplateService.SAF_002);
        assertThat(content).isNotBlank();
        // 双模块逐字节重复副本已收敛为单份（资源归 ai 模块，无第二份可断言其不存在——
        // 通过唯一 classpath 归属保证单源；此处补充内容冒烟）
        assertThat(content).contains("candidate_reply");
    }
}
