package com.mindsafe.common.enums;

/**
 * 风险等级（对齐 design/04 红橙黄绿四级）
 */
public enum RiskLevel {

    /** 绿色：普通烦恼，无安全风险 */
    GREEN(0, "安全"),
    /** 黄色：中度压力，需关注 */
    YELLOW(1, "关注"),
    /** 橙色：较高风险，转人工评估 */
    ORANGE(2, "预警"),
    /** 红色：即时危险，立即转人工 */
    RED(3, "紧急");

    private final int severity;
    private final String label;

    RiskLevel(int severity, String label) {
        this.severity = severity;
        this.label = label;
    }

    public int severity() {
        return severity;
    }

    public String label() {
        return label;
    }

    public boolean isHighRisk() {
        return this == RED || this == ORANGE;
    }
}
