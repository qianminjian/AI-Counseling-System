package com.mindsafe.service.assessment;

import java.util.Set;

/**
 * 内置量表 scoring_rules 定义（SCALE-001，design/34 §4.2/§1.2）
 * <p>
 * PHQ-A / GAD-7 为 Pfizer 声明免费使用量表（保留署名不改题目），
 * bands 完全照抄原始验证文献 cut-off（norm_reference 记录出处，可审计）。
 * <p>
 * 本类仅提供 scoring_rules JSON 与 critical 条目元数据，
 * 供计分引擎测试金标准用例与未来量表种子数据使用。
 * 施测接线暂缓（2026-07-28 决策），不注册为数据库量表记录。
 */
public final class BuiltinScales {

    private BuiltinScales() {
    }

    // ==================== PHQ-A（PHQ-9 青少年版） ====================

    /** PHQ-A 量表编码 */
    public static final String PHQA_CODE = "phqa";

    /**
     * PHQ-A scoring_rules（design/34 §4.2 示例，Johnson 2002 cut-off）
     * <p>
     * 11 题 Likert 4 点（0-3），总分 0-33（设计取 0-27 为原始 9 题 PHQ-9 范围，
     * PHQ-A 附加 2 题功能损害不计入总分——本 JSON 仅含 9 题核心计分）。
     */
    public static final String PHQA_SCORING_RULES = """
            {
              "method": "sum",
              "dimensions": {
                "depression": {
                  "items": ["phqa_1","phqa_2","phqa_3","phqa_4","phqa_5","phqa_6","phqa_7","phqa_8","phqa_9"],
                  "method": "sum"
                }
              },
              "bands": [
                {"min": 0,  "max": 4,  "level": "none",       "label": "无明显症状", "alert": null},
                {"min": 5,  "max": 9,  "level": "mild",       "label": "轻度",       "alert": "S3"},
                {"min": 10, "max": 14, "level": "moderate",   "label": "中度",       "alert": "S2"},
                {"min": 15, "max": 19, "level": "mod_severe", "label": "中重度",     "alert": "S1"},
                {"min": 20, "max": 27, "level": "severe",     "label": "重度",       "alert": "S1"}
              ],
              "critical_item_alert": "S0",
              "norm_reference": "Johnson 2002 PHQ-A validation; Fonseca-Pedrero 2023 school-based"
            }
            """;

    /** PHQ-A 关键条目（第 9 题自杀意念，critical_threshold=1：选"有几天"及以上即触发） */
    public static final Set<String> PHQA_CRITICAL_ITEMS = Set.of("phqa_9");

    // ==================== GAD-7（广泛性焦虑障碍量表） ====================

    /** GAD-7 量表编码 */
    public static final String GAD7_CODE = "gad7";

    /**
     * GAD-7 scoring_rules（Spitzer 2006 cut-off）
     * <p>
     * 7 题 Likert 4 点（0-3），总分 0-21。
     */
    public static final String GAD7_SCORING_RULES = """
            {
              "method": "sum",
              "dimensions": {
                "anxiety": {
                  "items": ["gad7_1","gad7_2","gad7_3","gad7_4","gad7_5","gad7_6","gad7_7"],
                  "method": "sum"
                }
              },
              "bands": [
                {"min": 0,  "max": 4,  "level": "minimal",  "label": "无/极轻", "alert": null},
                {"min": 5,  "max": 9,  "level": "mild",     "label": "轻度",   "alert": "S3"},
                {"min": 10, "max": 14, "level": "moderate", "label": "中度",   "alert": "S2"},
                {"min": 15, "max": 21, "level": "severe",   "label": "重度",   "alert": "S1"}
              ],
              "critical_item_alert": "S0",
              "norm_reference": "Spitzer 2006 GAD-7 validation"
            }
            """;

    /** GAD-7 无关键条目（自杀意念条目属 PHQ-A 而非 GAD-7） */
    public static final Set<String> GAD7_CRITICAL_ITEMS = Set.of();
}
