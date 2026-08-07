package com.mindsafe.service.conversation.strategy;

import com.mindsafe.ai.safety.CrisisResourceProvider;
import com.mindsafe.ai.safety.CrisisResources;
import com.mindsafe.common.enums.RiskLevel;

/**
 * 风险响应策略（DC-010，doing/72 §24）
 * <p>
 * 从 ConversationServiceImpl 编排层下沉的纯静态决策：RED 硬短路文案（年级适配）与
 * AUTH-030 时长超限引导语。零 Spring 依赖，可独立单测。
 */
public final class RiskResponseStrategy {

    private RiskResponseStrategy() {
    }

    /**
     * 解析安全回复文案（RISK-201）：
     * <ul>
     *   <li>RED → 预审核危机文案（年级适配，provider 注入——危机资源属于域外能力）</li>
     *   <li>安全模式（非本轮 RED）→ 陪伴话术（RED 后后续轮次不自由生成）</li>
     *   <li>其他 → null（走正常 LLM 链路）</li>
     * </ul>
     */
    public static String resolveSafetyReply(RiskLevel fusedLevel, boolean inSafetyMode,
                                            int grade, CrisisResourceProvider provider) {
        if (fusedLevel == RiskLevel.RED) {
            return provider.getRedSafetyReply(grade);
        }
        if (inSafetyMode) {
            return CrisisResources.SAFETY_MODE_COMPANION_REPLY;
        }
        return null;
    }

    /**
     * AUTH-030：每日使用时长超限引导语（含心理援助热线，文案预审核不可由 LLM 生成）。
     */
    public static String buildTimeLimitGuidance() {
        return "今天我们聊了不少啦，你已经很棒了。为了让眼睛和心情都休息一下，今天就先到这里好吗？"
                + "明天我还在这里等你。\uD83C\uDF19 如果现在有紧急的事情，可以告诉老师，或拨打心理援助热线 12355。";
    }
}
