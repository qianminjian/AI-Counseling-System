package com.mindsafe.ai.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prompt 模板服务：从 classpath 加载 .md 模板文件，运行时注入变量。
 * <p>
 * 模板变量使用 {{variable}} 双花括号标记（对齐 design/18 约定）。
 * 模板文件启动时加载并缓存，运行时仅做字符串替换，零 IO。
 */
@Service
public class PromptTemplateService {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateService.class);

    /** 模板缓存（key=模板路径，value=原始模板文本） */
    private final Map<String, String> templateCache = new ConcurrentHashMap<>();

    /**
     * 加载模板并注入变量
     *
     * @param templatePath classpath 相对路径，如 "prompts/system/system_student_companion_zh-CN_v1.0.0.md"
     * @param variables    变量映射（key 不含花括号）
     * @return 渲染后的完整 prompt 文本
     */
    public String render(String templatePath, Map<String, String> variables) {
        String template = getTemplate(templatePath);
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue() != null ? entry.getValue() : "");
        }
        return result;
    }

    /**
     * 加载原始模板（不注入变量）
     */
    public String getTemplate(String templatePath) {
        return templateCache.computeIfAbsent(templatePath, this::loadFromClasspath);
    }

    private String loadFromClasspath(String path) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            try (InputStream is = resource.getInputStream()) {
                String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                log.info("Prompt 模板已加载: {} ({} chars)", path, content.length());
                return content;
            }
        } catch (IOException e) {
            log.error("Prompt 模板加载失败: {}", path, e);
            throw new IllegalStateException("无法加载 Prompt 模板: " + path, e);
        }
    }

    // ===== 模板路径常量 =====

    public static final String SYS_001 = "prompts/system/system_student_companion_zh-CN_v1.0.0.md";
    public static final String SAF_001 = "prompts/safety/safety_risk_classifier_zh-CN_v1.0.0.md";
    public static final String SAF_002 = "prompts/safety/safety_output_guard_zh-CN_v1.0.0.md";
    public static final String LANG_001 = "prompts/language/child_language_grade_1_2_zh-CN_v1.0.0.md";
    public static final String LANG_002 = "prompts/language/child_language_grade_3_4_zh-CN_v1.0.0.md";
    public static final String LANG_003 = "prompts/language/child_language_grade_5_6_zh-CN_v1.0.0.md";
    public static final String SKL_001 = "prompts/skills/cbt_micro_skill_zh-CN_v1.0.0.md";
    public static final String SKL_002 = "prompts/skills/sel_guidance_zh-CN_v1.0.0.md";
    public static final String SKL_003 = "prompts/skills/pfa_stabilize_zh-CN_v1.0.0.md";
    public static final String TSK_001 = "prompts/tasks/teacher_summary_zh-CN_v1.0.0.md";
    public static final String TSK_002 = "prompts/tasks/rag_query_rewrite_zh-CN_v1.0.0.md";
    public static final String TSK_003 = "prompts/tasks/session_close_zh-CN_v1.0.0.md";

    /**
     * 根据年级获取对应语言模板路径
     */
    public static String languageTemplateForGrade(int grade) {
        if (grade <= 2) return LANG_001;
        if (grade <= 4) return LANG_002;
        return LANG_003;
    }
}
