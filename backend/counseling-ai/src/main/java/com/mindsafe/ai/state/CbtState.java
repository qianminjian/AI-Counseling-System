package com.mindsafe.ai.state;

/**
 * CBT 状态机状态枚举（对齐 design/13 §10.2 状态映射表）
 * <p>
 * S0~S9 对应完整 CBT 干预流程，END 为终态。
 */
public enum CbtState {

    /** 会话创建，等待用户参与 */
    S0_START("开始", "会话创建，等待用户确认参与"),

    /** 风险前置检查（每次用户输入后触发） */
    S1_SAFETY_PRECHECK("风险前置", "安全预检：R0/R1 进入 CBT；R2 限制性支持；R3+ 转人工"),

    /** 情绪命名：获得主情绪 + 强度 */
    S2_EMOTION_LABEL("情绪命名", "识别并命名主情绪及强度"),

    /** 场景路由：匹配 CBT 场景流程树 */
    S3_SCENARIO_ROUTE("场景路由", "根据情绪和情境匹配干预场景"),

    /** 事件确认：了解触发事件 + 安全状态 */
    S4_EVENT_FACT("事件确认", "收集触发事件的事实信息"),

    /** 自动想法：捕捉负面自动思维 */
    S5_AUTO_THOUGHT("自动想法", "识别儿童的自动化负面想法"),

    /** 认知重构：建立替代性平衡想法 */
    S6_REFRAME("认知重构", "引导儿童产生替代性平衡想法"),

    /** 微行动：选择可执行的小行动 */
    S7_MICRO_ACTION("微行动", "儿童选择一个具体可执行的微行动"),

    /** 复检结束：情绪复评 + 风险稳定确认 */
    S8_RECHECK_CLOSE("复检结束", "情绪降/风险稳定后收束会话"),

    /** 转人工：任意状态 R3/R4 强制升级 */
    S9_ESCALATE("转人工", "高风险升级，通知心理教师"),

    /** 终态 */
    END("结束", "会话正常结束");

    private final String label;
    private final String description;

    CbtState(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    /** 是否为终态 */
    public boolean isTerminal() {
        return this == END || this == S9_ESCALATE;
    }

    /** 是否为 CBT 干预活跃状态（S4~S7） */
    public boolean isInterventionActive() {
        return this == S4_EVENT_FACT || this == S5_AUTO_THOUGHT
                || this == S6_REFRAME || this == S7_MICRO_ACTION;
    }

    /**
     * 从字符串解析状态（兼容旧数据中的字符串格式如 "S2_EMOTION_LABEL"）
     */
    public static CbtState fromString(String value) {
        if (value == null || value.isBlank()) {
            return S0_START;
        }
        try {
            return CbtState.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            // 兼容旧格式：如 "IDLE" → S0_START
            return switch (value.trim().toUpperCase()) {
                case "IDLE" -> S0_START;
                case "ENGAGING" -> S2_EMOTION_LABEL;
                case "ASSESSING" -> S4_EVENT_FACT;
                case "INTERVENING" -> S5_AUTO_THOUGHT;
                case "MONITORING" -> S7_MICRO_ACTION;
                case "CLOSING" -> S8_RECHECK_CLOSE;
                case "ESCALATING" -> S9_ESCALATE;
                default -> S0_START;
            };
        }
    }
}
