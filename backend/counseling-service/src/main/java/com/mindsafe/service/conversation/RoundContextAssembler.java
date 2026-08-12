package com.mindsafe.service.conversation;

import com.mindsafe.ai.ally.AllianceEnhancer;
import com.mindsafe.service.memory.LongTermMemoryService;
import com.mindsafe.service.profile.StudentProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 每轮上下文组装单点（S-002，doing/93；P1-2 从 ConversationServiceImpl 抽离）。
 * <p>
 * 主链路与 nudge 双路径共用（原逐字重复五连调用且已分叉）；
 * contextBrief 注入时机由调用方决定（主链路拼入 system 尾部；nudge 交 chatProactive）。
 * 职责：年级计算 + 画像提示 + 长期记忆 + 联盟增强 + CTX 简报（PROF-010/011/012/015 + AI-008 + ALLY-201/203 + CTX-Agent）。
 */
@Component
public class RoundContextAssembler {

    private static final Logger log = LoggerFactory.getLogger(RoundContextAssembler.class);

    private final StudentProfileService profileService;
    private final LongTermMemoryService longTermMemoryService;
    private final AllianceEnhancer allianceEnhancer;
    private final ConversationContextAgent contextAgent;

    public RoundContextAssembler(StudentProfileService profileService,
                                 LongTermMemoryService longTermMemoryService,
                                 AllianceEnhancer allianceEnhancer,
                                 ConversationContextAgent contextAgent) {
        this.profileService = profileService;
        this.longTermMemoryService = longTermMemoryService;
        this.allianceEnhancer = allianceEnhancer;
        this.contextAgent = contextAgent;
    }

    /** S-002（doing/93）：每轮上下文组装产物（年级 + 画像 + 记忆 + 联盟 + 简报） */
    public record RoundContext(int effectiveGrade, String profilePrompt, String memoryPrompt,
                               String alliancePrompt, String contextBrief) {
    }

    /**
     * S-002（doing/93）：每轮上下文组装单点——画像 + 长期记忆 + 联盟增强 + 会话数 + CTX 简报。
     */
    public RoundContext build(SessionState session, boolean riskBlocked) {
        int effectiveGrade = ConversationUtils.computeEffectiveGrade(
                session.getGrade(), session.getExpressionDepth(), riskBlocked);
        // PROF-010/011/012/015：学生画像提示（年级适配）
        String profilePrompt = profileService.buildProfilePrompt(
                session.getTenantId(), session.getStudentUserId(), session.getGrade(), session.getGender());
        // AI-008：长期记忆（跨会话关键事件回注）
        String memoryPrompt = longTermMemoryService.buildMemoryPrompt(
                session.getTenantId(), session.getStudentUserId());
        // ALLY-201/203：治疗联盟增强——连续性开场 + 中断回归照护（design/52 §五）
        String alliancePrompt = buildAlliancePrompt(session, memoryPrompt);
        // CTX-Agent：结构化上下文简报（身份+情绪旅程+会话进展+记忆+画像）
        int totalSessions = profileService.getSessionCount(session.getTenantId(), session.getStudentUserId());
        String contextBrief = contextAgent.buildContextBrief(
                session, profilePrompt, memoryPrompt, alliancePrompt, totalSessions);
        return new RoundContext(effectiveGrade, profilePrompt, memoryPrompt, alliancePrompt, contextBrief);
    }

    /**
     * ALLY-201/203：构建治疗联盟增强 Prompt（连续性开场 + 中断回归照护）。
     * <p>
     * 利用记忆回注摘要生成续接话术；失败安全：无记忆 → 返回 null（不注入）。
     */
    private String buildAlliancePrompt(SessionState session, String memoryPrompt) {
        try {
            // ALLY-201：连续性开场（有记忆回注时生成续接提示）
            if (memoryPrompt != null && !memoryPrompt.isBlank()) {
                String firstLine = memoryPrompt.lines()
                        .filter(l -> l.startsWith("- "))
                        .findFirst()
                        .map(l -> l.substring(2).trim())
                        .orElse(null);
                return allianceEnhancer.buildContinuityPrompt(firstLine, "波波");
            }
            return null;
        } catch (Exception e) {
            log.debug("ALLY 联盟增强构建失败（不影响对话）: {}", e.getMessage());
            return null;
        }
    }
}
