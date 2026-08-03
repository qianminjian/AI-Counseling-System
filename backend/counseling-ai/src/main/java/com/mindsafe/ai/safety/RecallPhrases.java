package com.mindsafe.ai.safety;

/**
 * Layer2 召回替换话术（预审核模板，不由 LLM 现场生成）。
 * <p>
 * 对齐 design/14 §12.2（SAFE-202）：流式消息已出后 Layer2 审查发现违规时，
 * 用预审核话术替换已落库/落记忆的回复。儿童视角为「波波换了个说法」。
 * <ul>
 *   <li>block（诊断话术/保密违规/危险内容等 1-6 项违规）→ {@link #BLOCK_RECALL}；</li>
 *   <li>escalate（学生高风险但回复未做安全处置）→ {@link #ESCALATE_RECALL}（含热线，铁律同 CrisisResources）。</li>
 * </ul>
 * rewrite 档（仅适龄性轻微违规）使用 SAF-002 返回的 rewritten_reply，不使用本类常量。
 */
public final class RecallPhrases {

    private RecallPhrases() {}

    /** block 档召回话术：撤回不当表述，回到陪伴定位，不做诊断/不承诺保密 */
    public static final String BLOCK_RECALL = """
            波波想重新说一下：我刚才的话可能不太合适。
            我不是医生，不能给你下结论；你说的话，老师一般看不到，但如果你有危险，我一定会告诉能保护你的大人。
            我们重新聊聊，好吗？""";

    /** escalate 档召回话术：学生高风险但原回复未做安全处置，替换为安全处置话术（含热线硬编码） */
    public static final String ESCALATE_RECALL = """
            波波重新认真地说：谢谢你告诉我，这件事你不用一个人扛。
            你现在安全吗？身边有没有老师、家长或你信得过的大人？
            我会把这件事告诉能保护你的老师。也可以拨打全国心理援助热线 400-161-9995（24 小时都有人接）。""";
}
