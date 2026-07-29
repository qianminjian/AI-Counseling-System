package com.mindsafe.service.assessment;

import java.util.List;
import java.util.Map;

/**
 * 量表计分结果（SCALE-001，design/34 §4.2/§六）
 * <p>
 * 由 {@link AssessmentScoringEngine#score} 产出，纯规则计算零 LLM，确定性可复现。
 *
 * @param totalScore        总分（各题 score_value 之和）
 * @param dimensionScores   各维度分（key=维度名，value=该维度题目分数之和）
 * @param bandLevel         分档级别（bands[].level，如 none/mild/moderate/mod_severe/severe）
 * @param bandLabel         分档中文标签（bands[].label，如"轻度"/"中度"）
 * @param alertLevel        预警等级（S0/S1/S2/S3 或 null=无需预警）
 * @param criticalTriggered 关键条目是否触发即时熔断（design/34 §六）
 * @param criticalItemIds   触发熔断的关键条目 ID 列表（空=未触发）
 */
public record ScoringResult(
        int totalScore,
        Map<String, Integer> dimensionScores,
        String bandLevel,
        String bandLabel,
        String alertLevel,
        boolean criticalTriggered,
        List<String> criticalItemIds
) {

    /** 是否产生预警（S0-S3 任何级别） */
    public boolean hasAlert() {
        return alertLevel != null;
    }

    /** 是否为最高紧急预警（关键条目熔断或高危分档） */
    public boolean isS0() {
        return "S0".equals(alertLevel);
    }
}
