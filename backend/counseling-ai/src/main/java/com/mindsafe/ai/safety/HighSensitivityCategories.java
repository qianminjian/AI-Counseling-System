package com.mindsafe.ai.safety;

import com.mindsafe.ai.risk.RiskKeywordRegistry;
import org.springframework.stereotype.Component;


/**
 * 高敏场景类别注册表（SAFE-202，design/52 §三：Layer2 高敏前置化/触发关注）
 * <p>
 * 当风险检测命中这些类别（即使仅 YELLOW 级别），会话标记为"高敏模式"：
 * <ul>
 *   <li>编排层：pace=SLOW、禁止追问细节、增加共情权重</li>
 *   <li>教师端：触发"关注"信号（非预警，仅提示留意）</li>
 *   <li>后续轮次：风险检测阈值心理降低（宁多报不漏报）</li>
 * </ul>
 * <p>
 * 与 RED 硬短路的区别：高敏不短路 LLM，只调整策略权重；
 * 与 ORANGE 的区别：高敏是"话题敏感性"而非"即时危险"。
 * <p>
 * DC-001（doing/72 §16）：类别单一源委托 {@link RiskKeywordRegistry#isHighSensitivityCategory}——
 * 中文权威类别收敛于 RiskKeywordRegistry.HIGH_SENSITIVITY_CATEGORIES（原英文常量集已删除，
 * 英文类别在生产判定链中从不出现，SAFE-202 门控恒 false 根因）。
 */
@Component
public class HighSensitivityCategories {

    private final RiskKeywordRegistry riskKeywords;

    public HighSensitivityCategories(RiskKeywordRegistry riskKeywords) {
        this.riskKeywords = riskKeywords;
    }

    /**
     * 判断给定风险类别是否属于高敏场景。
     *
     * @param category 风险类别标识（来自 RiskDetectionResult.category()，中文类别）
     * @return true=高敏类别
     */
    public boolean isHighSensitivity(String category) {
        return riskKeywords.isHighSensitivityCategory(category);
    }
}
