package com.mindsafe.service.assessment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 量表计分引擎（SCALE-001，design/34 §4.2/§六/§十 M1）
 * <p>
 * scoring_rules jsonb 规则解释器：解析 bands/dimensions/critical_item_alert，
 * 对作答 map 做确定性计分，零 LLM 调用（MBC 前提：可复现、可审计）。
 * <p>
 * 支持计分方法：
 * <ul>
 *   <li>{@code sum}：各题分数直接求和 → 总分匹配 bands（PHQ-A/GAD-7）</li>
 *   <li>{@code banded_dimensions}：各维度独立求和+分档（SDQ，M3 扩展预留）</li>
 * </ul>
 * <p>
 * 关键条目即时熔断（design/34 §六）：critical 条目作答值 ≥ 阈值 →
 * 不等整卷完成，alertLevel 强制 critical_item_alert（通常 S0），覆盖 bands 分档。
 * <p>
 * 新增量表零代码（开闭原则）：只需配置 scoring_rules JSON，引擎不变。
 */
@Component
public class AssessmentScoringEngine {

    private static final Logger log = LoggerFactory.getLogger(AssessmentScoringEngine.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 对一份作答执行计分。
     *
     * @param scoringRulesJson scoring_rules jsonb 字符串（design/34 §4.2 格式）
     * @param responses        作答映射（itemId → score_value，即选项 value）
     * @param criticalItemIds  关键条目 ID 集合（来自 item_schema 中 critical=true 的条目，
     *                         调用方已按 critical_threshold 过滤；空集=无关键条目）
     * @return 计分结果（总分+维度分+分档+预警等级+熔断标志）
     * @throws IllegalArgumentException scoringRulesJson 解析失败或格式不合法
     */
    public ScoringResult score(String scoringRulesJson, Map<String, Integer> responses,
                               Set<String> criticalItemIds) {
        if (scoringRulesJson == null || scoringRulesJson.isBlank()) {
            throw new IllegalArgumentException("scoring_rules 不能为空");
        }
        if (responses == null) {
            responses = Map.of();
        }
        if (criticalItemIds == null) {
            criticalItemIds = Set.of();
        }

        JsonNode rules = parseRules(scoringRulesJson);
        String method = rules.path("method").asText("sum");

        // 1. 关键条目即时熔断检测（design/34 §六：不等整卷，优先于分档）
        List<String> triggeredCritical = detectCritical(responses, criticalItemIds);

        // 2. 计分
        int totalScore;
        Map<String, Integer> dimensionScores;
        if ("banded_dimensions".equals(method)) {
            dimensionScores = scoreDimensions(rules, responses);
            totalScore = dimensionScores.values().stream().mapToInt(Integer::intValue).sum();
        } else {
            // sum 方法（PHQ-A/GAD-7）：所有作答值直接求和
            totalScore = responses.values().stream().mapToInt(Integer::intValue).sum();
            dimensionScores = computeDimensions(rules, responses);
        }

        // 3. 分档匹配
        BandMatch band = matchBand(rules, totalScore);

        // 4. 预警等级裁决：critical 熔断 > bands.alert
        String alertLevel;
        if (!triggeredCritical.isEmpty()) {
            alertLevel = rules.path("critical_item_alert").asText("S0");
            log.warn("🚨 量表关键条目熔断: items={}, alert={}", triggeredCritical, alertLevel);
        } else {
            alertLevel = band.alert;
        }

        return new ScoringResult(
                totalScore,
                dimensionScores,
                band.level,
                band.label,
                alertLevel,
                !triggeredCritical.isEmpty(),
                triggeredCritical
        );
    }

    // ==================== 内部方法 ====================

    private JsonNode parseRules(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            if (!node.has("bands") || !node.get("bands").isArray()) {
                throw new IllegalArgumentException("scoring_rules 缺少 bands 数组");
            }
            return node;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("scoring_rules JSON 解析失败: " + e.getMessage(), e);
        }
    }

    /** 关键条目检测：responses 中属于 criticalItemIds 且值 > 0 的条目视为触发 */
    private List<String> detectCritical(Map<String, Integer> responses, Set<String> criticalItemIds) {
        List<String> triggered = new ArrayList<>();
        for (String itemId : criticalItemIds) {
            Integer value = responses.get(itemId);
            if (value != null && value > 0) {
                triggered.add(itemId);
            }
        }
        return triggered;
    }

    /** sum 方法下按 dimensions 配置计算各维度分（无 dimensions 配置则返回空 map） */
    private Map<String, Integer> computeDimensions(JsonNode rules, Map<String, Integer> responses) {
        Map<String, Integer> result = new LinkedHashMap<>();
        JsonNode dimensions = rules.path("dimensions");
        if (!dimensions.isObject()) {
            return result;
        }
        dimensions.fields().forEachRemaining(entry -> {
            String dimName = entry.getKey();
            JsonNode dimConfig = entry.getValue();
            JsonNode items = dimConfig.path("items");
            int sum = 0;
            if (items.isArray()) {
                for (JsonNode itemNode : items) {
                    String itemId = itemNode.asText();
                    sum += responses.getOrDefault(itemId, 0);
                }
            }
            result.put(dimName, sum);
        });
        return result;
    }

    /** banded_dimensions 方法：各维度独立求和（SDQ M3 扩展，当前仅做求和，分档逻辑后续补） */
    private Map<String, Integer> scoreDimensions(JsonNode rules, Map<String, Integer> responses) {
        return computeDimensions(rules, responses);
    }

    /** 总分匹配 bands 分档（取第一个 min ≤ score ≤ max 的 band） */
    private BandMatch matchBand(JsonNode rules, int totalScore) {
        JsonNode bands = rules.get("bands");
        for (JsonNode band : bands) {
            int min = band.path("min").asInt(0);
            int max = band.path("max").asInt(Integer.MAX_VALUE);
            if (totalScore >= min && totalScore <= max) {
                String alert = band.has("alert") && !band.get("alert").isNull()
                        ? band.get("alert").asText()
                        : null;
                return new BandMatch(
                        band.path("level").asText("unknown"),
                        band.path("label").asText(""),
                        alert
                );
            }
        }
        // 未匹配任何 band（理论上不应发生，量表 bands 应覆盖全域）
        log.warn("量表计分未匹配任何分档: totalScore={}", totalScore);
        return new BandMatch("unknown", "未分档", null);
    }

    private record BandMatch(String level, String label, String alert) {
    }
}
