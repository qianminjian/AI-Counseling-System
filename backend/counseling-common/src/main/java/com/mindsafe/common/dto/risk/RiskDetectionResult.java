package com.mindsafe.common.dto.risk;

import com.mindsafe.common.enums.RiskLevel;

import java.util.List;

/**
 * 风险检测结果
 *
 * @param level          风险等级
 * @param category       风险类别（如：自伤/自杀、霸凌、家庭虐待等）
 * @param matchedKeywords 命中的关键词
 * @param score          风险评分（0-100）
 * @param hardUpgrade    是否触发强制升级
 * @param suggestion     系统建议动作
 */
public record RiskDetectionResult(
        RiskLevel level,
        String category,
        List<String> matchedKeywords,
        int score,
        boolean hardUpgrade,
        String suggestion
) {

    /** 安全（无风险） */
    public static RiskDetectionResult safe() {
        return new RiskDetectionResult(RiskLevel.GREEN, null, List.of(), 0, false, null);
    }

    /** 是否触发风险 */
    public boolean isRisky() {
        return level != RiskLevel.GREEN;
    }

    /** 是否需要通知教师 */
    public boolean shouldNotifyTeacher() {
        return level.isHighRisk();
    }
}
