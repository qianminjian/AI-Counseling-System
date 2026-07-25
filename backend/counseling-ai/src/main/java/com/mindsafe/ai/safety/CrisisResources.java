package com.mindsafe.ai.safety;

/**
 * 危机资源常量（硬编码，不由 LLM 生成）
 * <p>
 * 对齐 design/18 §12.3：所有危机热线固化在代码中，
 * Safety Agent 在 L4/L5 场景直接引用，防止 LLM 幻觉篡改号码。
 */
public final class CrisisResources {

    private CrisisResources() {}

    /** 全国心理援助热线（24 小时） */
    public static final String NATIONAL_PSYCHOLOGICAL_AID = "400-161-9995";

    /** 生命热线 */
    public static final String LIFE_HOTLINE = "400-821-1215";

    /** 妇联家暴投诉 */
    public static final String WOMEN_FEDERATION_DOMESTIC_VIOLENCE = "12338";

    /** 反家暴援助 */
    public static final String ANTI_DOMESTIC_VIOLENCE = "400-828-1112";

    /** 性侵支持热线 */
    public static final String SEXUAL_ASSAULT_SUPPORT = "400-0133-123";

    /** 急救 */
    public static final String EMERGENCY_MEDICAL = "120";

    /** 报警 */
    public static final String EMERGENCY_POLICE = "110";

    /** L4 高风险安全回复（硬编码模板） */
    public static final String L4_SAFETY_REPLY = """
            谢谢你告诉我，这件事你不用一个人扛。
            你现在安全吗？身边有没有老师、家长或你信得过的大人？
            我会把这件事告诉能保护你的老师。在老师来之前，请先待在安全的地方。""";

    /** L5 紧急风险安全回复（硬编码模板） */
    public static final String L5_SAFETY_REPLY = """
            我很在意你现在的安全。
            请你现在就找身边的老师或家人，告诉他们你需要帮助。
            如果很紧急，可以拨打 120（急救）或 110（报警）。
            全国心理援助热线：400-161-9995（24 小时）。
            我已经通知了学校老师，他们会马上来帮你。""";
}
