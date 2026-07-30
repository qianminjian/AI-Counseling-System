package com.mindsafe.ai.safety;

import java.util.Set;

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
 */
public final class HighSensitivityCategories {

    private HighSensitivityCategories() {
    }

    /** 高敏类别集（命中即标记，不论级别） */
    private static final Set<String> CATEGORIES = Set.of(
            "physical_abuse",       // 躯体虐待
            "sexual_abuse",         // 性侵/性骚扰
            "domestic_violence",    // 家暴
            "neglect",              // 忽视/遗弃
            "bereavement",          // 丧失/亲人离世
            "self_harm",            // 自伤（非即时也需高敏关注）
            "suicidal_ideation"     // 自杀意念（低强度也需高敏）
    );

    /**
     * 判断给定风险类别是否属于高敏场景。
     *
     * @param category 风险类别标识（来自 RiskDetectionResult.category()）
     * @return true=高敏类别
     */
    public static boolean isHighSensitivity(String category) {
        return category != null && CATEGORIES.contains(category);
    }
}
