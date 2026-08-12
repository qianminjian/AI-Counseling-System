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
    public static final String TSK_004 = "prompts/tasks/proactive_nudge_zh-CN_v1.0.0.md";
    public static final String EMO_001 = "prompts/emotion/emotion_strategy_zh-CN_v1.0.0.md";
    // P0-1 ①：AiChatServiceImpl 4 个辅助 LLM prompt 下沉 prompts/aux/（会话摘要/洞察提炼/质量评估/进展摘要），
    // 与既有模板同目录管理（版本路由语义预留：注册进 PromptVersionService.KEY_TO_CLASSPATH 即可纳入 DB 版本覆盖）
    public static final String AUX_001 = "prompts/aux/session_summary_zh-CN_v1.0.0.md";
    public static final String AUX_002 = "prompts/aux/conversation_insights_zh-CN_v1.0.0.md";
    public static final String AUX_003 = "prompts/aux/quality_judge_zh-CN_v1.0.0.md";
    public static final String AUX_004 = "prompts/aux/session_progress_summary_zh-CN_v1.0.0.md";
    // B4：性别×年级沟通风格模板（PROF-014，design/29 §3.10），文案下沉 prompts/ 后经版本路由
    public static final String GENDER_STYLE_MALE_LOW = "prompts/style/gender_style_male_low_zh-CN_v1.0.0.md";
    public static final String GENDER_STYLE_MALE_MID = "prompts/style/gender_style_male_mid_zh-CN_v1.0.0.md";
    public static final String GENDER_STYLE_MALE_HIGH = "prompts/style/gender_style_male_high_zh-CN_v1.0.0.md";
    public static final String GENDER_STYLE_FEMALE_LOW = "prompts/style/gender_style_female_low_zh-CN_v1.0.0.md";
    public static final String GENDER_STYLE_FEMALE_MID = "prompts/style/gender_style_female_mid_zh-CN_v1.0.0.md";
    public static final String GENDER_STYLE_FEMALE_HIGH = "prompts/style/gender_style_female_high_zh-CN_v1.0.0.md";
    public static final String GENDER_STYLE_NEUTRAL_LOW = "prompts/style/gender_style_neutral_low_zh-CN_v1.0.0.md";
    public static final String GENDER_STYLE_NEUTRAL_MID = "prompts/style/gender_style_neutral_mid_zh-CN_v1.0.0.md";
    public static final String GENDER_STYLE_NEUTRAL_HIGH = "prompts/style/gender_style_neutral_high_zh-CN_v1.0.0.md";

    /**
     * 根据年级获取对应语言模板路径
     */
    public static String languageTemplateForGrade(int grade) {
        if (grade <= 2) return LANG_001;
        if (grade <= 4) return LANG_002;
        return LANG_003;
    }
}
