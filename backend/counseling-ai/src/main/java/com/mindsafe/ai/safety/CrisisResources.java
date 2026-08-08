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

    /**
     * L5 紧急风险安全回复（硬编码模板，{hotline} 由 CrisisHotlineProvider 渲染）
     * 注意：本模板当前无消费点（预审核话术库）；未来接线时必须经 render() 渲染后再输出，禁止直出含占位符文本。
     */
    public static final String L5_SAFETY_REPLY = """
            我很在意你现在的安全。
            请你现在就找身边的老师或家人，告诉他们你需要帮助。
            如果很紧急，可以拨打 120（急救）或 110（报警）。
            全国心理援助热线：{hotline}（24 小时）。
            我已经通知了学校老师，他们会马上来帮你。""";

    // ===== RISK-201：RED 硬短路安全回复（design/04 §18.2，预审核模板，不由 LLM 生成） =====

    /** RED 短路安全回复——3-6 年级标准版（接住情绪 → 告知会找大人 → 引导身边成人 → 热线；{hotline} 由 CrisisHotlineProvider 渲染） */
    public static final String RED_SAFETY_REPLY = """
            谢谢你愿意告诉我，我很在意你现在的安全。
            我已经把这件事告诉了能保护你的老师，老师会很快来帮你，你不是一个人。
            现在请你去找身边的老师、家长或你信得过的大人，待在他们身边。
            如果很紧急，可以拨打 110（报警）或 120（急救），
            也可以拨打全国心理援助热线 {hotline}（24 小时都有人接）。""";

    /** RED 短路安全回复——1-2 年级短句版（每句 ≤15 字，认知匹配） */
    public static final String RED_SAFETY_REPLY_LOWER_GRADE = """
            谢谢你告诉我。
            我已经告诉老师了。
            老师马上就来帮你。
            你现在去找老师或爸爸妈妈。
            待在他们身边，好吗？
            你不是一个人。""";

    /** RED 短路后安全响应模式陪伴话术（escalated 后续轮次限制自由生成，等待教师处置） */
    public static final String SAFETY_MODE_COMPANION_REPLY = """
            我在这里陪着你，老师很快就来了。
            你现在先待在安全的地方，找到身边的大人。
            有什么话都可以慢慢告诉老师，你不是一个人。""";
}
