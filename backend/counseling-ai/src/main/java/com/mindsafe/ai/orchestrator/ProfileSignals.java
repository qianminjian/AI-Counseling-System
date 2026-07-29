package com.mindsafe.ai.orchestrator;

import java.util.List;

/**
 * 画像→编排的结构化信号（PROF-022，design/46 §5.1）
 * <p>
 * 由调用方（counseling-service）从学生画像 JSONB 的维度值 + {@code _meta} 元数据
 * （provenance/confidence/evidence_count）提取，供编排引擎做低优先级微调。
 * <p>
 * 置信门控（design/46 §5.2）：低置信画像维度不参与编排——宁可不用，不可乱用。
 * P0 范围仅接 introversion（→开场/节奏微调）与 dominant_interests（→镜映取材），
 * sensitivity/coping_skills 等映射为 P2 扩展。
 *
 * @param introversion          内向程度 0-1（personality_traits.introversion，可为 null）
 * @param introversionConfidence introversion 的置信度（取自 _meta，缺失为 0）
 * @param dominantInterests     主导兴趣标签（personality_traits.dominant_interests，可为空列表）
 * @param interestsConfidence   dominant_interests 的置信度（取自 _meta，缺失为 0）
 */
public record ProfileSignals(
        Double introversion,
        double introversionConfidence,
        List<String> dominantInterests,
        double interestsConfidence
) {

    /** 参与编排的最低置信度（evidence_count≥2 次 LLM 提炼后达到） */
    public static final double MIN_CONFIDENCE = 0.5;

    /** introversion 是否可参与编排微调 */
    public boolean introversionUsable() {
        return introversion != null && introversionConfidence >= MIN_CONFIDENCE;
    }

    /** dominant_interests 是否可参与镜映取材 */
    public boolean interestsUsable() {
        return dominantInterests != null && !dominantInterests.isEmpty()
                && interestsConfidence >= MIN_CONFIDENCE;
    }
}
