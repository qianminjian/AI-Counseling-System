package com.mindsafe.service.conversation;

import com.mindsafe.ai.cbt.CbtStageRouter;
import com.mindsafe.service.prompt.PromptVersionService;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Prompt 系统提示词组装服务（ARCH-001 C1：从 ConversationServiceImpl 拆出的深模块）。
 * <p>
 * 职责：Prompt 版本 A/B 路由（AI-005/ARCH-010 D4）+ 按固定顺序拼接 system prompt。
 * 主链路：SYS_001 → LANG_xxx → GENDER_STYLE → EMO_001 → CBT 指令 → RAG（空段省略）；
 * 暖场链路：SYS_001 → LANG_xxx → GENDER_STYLE → TSK_004。
 * <p>
 * 收敛后消除两处重复的 gradeLevel/langKey 计算（DRY），拼接顺序获得完整测试面；
 * 行为与原 ConversationServiceImpl 私有内联逻辑一致，仅收敛位置不调整语义。
 */
@Service
public class PromptAssemblyService {

    private static final String DEFAULT_SCHOOL_POLICY = "默认：发现高风险立即通知心理老师。";

    private final PromptVersionService promptVersionService;
    private final CbtStageRouter cbtStageRouter;

    public PromptAssemblyService(PromptVersionService promptVersionService, CbtStageRouter cbtStageRouter) {
        this.promptVersionService = promptVersionService;
        this.cbtStageRouter = cbtStageRouter;
    }

    /** 主链路组装结果：内容全文 + 版本标签（versionTag 供 A/B 对比与评估闭环落库，design/45） */
    public record AssembledPrompt(String content, String versionTag) {
    }

    /**
     * 主链路组装：SYS_001 + LANG_xxx + GENDER_STYLE + EMO_001 + CBT 指令 + RAG（顺序固定，AI-005/ARCH-010 D4）。
     *
     * @param gender         学生性别（male/female，可为 null；B4：性别风格文案下沉 prompts/ 模板经版本路由）
     * @param emoTemplateVars EMO_001 模板变量（来自编排引擎策略，调用方经 toTemplateVariables 生成）
     * @param stageMark       CBT 阶段标记（调用方由 inferStage/mark 计算，此处仅取指令文本）
     * @param ragContext      RAG 参考知识，为空时省略该段
     */
    public AssembledPrompt assembleMainPrompt(UUID tenantId, UUID studentUserId, int effectiveGrade,
                                              String emotionTag, Map<String, String> emoTemplateVars,
                                              CbtStageRouter.StageMark stageMark, String ragContext, String gender) {
        PromptVersionService.ResolvedPrompt sysResolved = resolveSys(tenantId, studentUserId, effectiveGrade, emotionTag);
        PromptVersionService.ResolvedPrompt langResolved = promptVersionService.resolveRaw(
                tenantId, langKeyOf(effectiveGrade), studentUserId);
        PromptVersionService.ResolvedPrompt styleResolved = promptVersionService.resolve(
                tenantId, genderStyleKeyOf(gender, effectiveGrade), studentUserId, Map.of());
        PromptVersionService.ResolvedPrompt emoResolved = promptVersionService.resolve(
                tenantId, "EMO_001", studentUserId, emoTemplateVars);

        StringBuilder prompt = new StringBuilder(sysResolved.content())
                .append("\n\n").append(langResolved.content())
                .append("\n\n").append(styleResolved.content())
                .append("\n\n").append(emoResolved.content())
                .append("\n\n").append(cbtStageRouter.stageDirective(stageMark));
        if (ragContext != null && !ragContext.isEmpty()) {
            prompt.append("\n\n").append(ragContext);
        }
        return new AssembledPrompt(prompt.toString(), sysResolved.versionTag());
    }

    /**
     * 暖场链路组装：SYS_001 + LANG_xxx + GENDER_STYLE + TSK_004（ARCH-010 D4 同一加载路径）。
     *
     * @param gender           学生性别（male/female，可为 null；B4 与主链路同一性别风格模板）
     * @param nudgeTemplateVars TSK_004 模板变量（silence_seconds/warmth_level/direction）
     */
    public String assembleNudgePrompt(UUID tenantId, UUID studentUserId, int effectiveGrade,
                                      String emotionTag, Map<String, String> nudgeTemplateVars, String gender) {
        PromptVersionService.ResolvedPrompt sysResolved = resolveSys(tenantId, studentUserId, effectiveGrade, emotionTag);
        PromptVersionService.ResolvedPrompt langResolved = promptVersionService.resolveRaw(
                tenantId, langKeyOf(effectiveGrade), studentUserId);
        PromptVersionService.ResolvedPrompt styleResolved = promptVersionService.resolve(
                tenantId, genderStyleKeyOf(gender, effectiveGrade), studentUserId, Map.of());
        PromptVersionService.ResolvedPrompt nudgeResolved = promptVersionService.resolve(
                tenantId, "TSK_004", studentUserId, nudgeTemplateVars);
        return sysResolved.content() + "\n\n" + langResolved.content() + "\n\n" + styleResolved.content()
                + "\n\n" + nudgeResolved.content();
    }

    /** SYS_001 版本路由（DB 优先 + A/B 灰度，AI-005/ARCH-010 D4） */
    private PromptVersionService.ResolvedPrompt resolveSys(UUID tenantId, UUID studentUserId,
                                                           int effectiveGrade, String emotionTag) {
        return promptVersionService.resolve(tenantId, "SYS_001", studentUserId, Map.of(
                "grade_level", gradeLevelOf(effectiveGrade),
                "emotion_tag", emotionTag != null ? emotionTag : "",
                "school_policy", DEFAULT_SCHOOL_POLICY,
                "session_mode", "normal_counseling"
        ));
    }

    /** 年级分层标签：≤2 → 1-2，3-4 → 3-4，≥5 → 5-6 */
    static String gradeLevelOf(int effectiveGrade) {
        return effectiveGrade <= 2 ? "1-2" : effectiveGrade <= 4 ? "3-4" : "5-6";
    }

    /** 年级对应语言模板键：≤2 → LANG_001，3-4 → LANG_002，≥5 → LANG_003 */
    static String langKeyOf(int effectiveGrade) {
        return effectiveGrade <= 2 ? "LANG_001" : effectiveGrade <= 4 ? "LANG_002" : "LANG_003";
    }

    /**
     * 性别风格模板键（B4）：GENDER_STYLE_{MALE|FEMALE|NEUTRAL}_{LOW|MID|HIGH}，
     * 与 gradeLevelOf 同边界（≤2 low / ≤4 mid / else high）；未指定性别归 NEUTRAL。
     */
    static String genderStyleKeyOf(String gender, int effectiveGrade) {
        String band = effectiveGrade <= 2 ? "LOW" : effectiveGrade <= 4 ? "MID" : "HIGH";
        String sex = "male".equals(gender) ? "MALE" : "female".equals(gender) ? "FEMALE" : "NEUTRAL";
        return "GENDER_STYLE_" + sex + "_" + band;
    }
}
