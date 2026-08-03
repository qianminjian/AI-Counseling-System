package com.mindsafe.ai.orchestrator;

import java.util.List;

/**
 * 画像→编排的结构化信号（PROF-025 + ORCH-006，design/46 §5.1）
 * <p>
 * 由调用方（counseling-service）从学生画像 JSONB 的维度值 + {@code _meta} 元数据
 * （provenance/confidence/evidence_count）提取，供编排引擎做低优先级微调。
 * <p>
 * 置信门控（design/46 §5.2）：低置信画像维度不参与编排——宁可不用，不可乱用。
 * <ul>
 *   <li>P0：introversion（→开场/节奏）、dominant_interests（→镜映取材）</li>
 *   <li>P2（ORCH-006）：sensitivity（→追问强度/温度）、curiosity（→探索式引导）、copingSkills（→技能复用）</li>
 * </ul>
 *
 * @param introversion           内向程度 0-1（personality_traits.introversion，可为 null）
 * @param introversionConfidence introversion 的置信度（取自 _meta，缺失为 0）
 * @param dominantInterests      主导兴趣标签（personality_traits.dominant_interests，可为空列表）
 * @param interestsConfidence    dominant_interests 的置信度（取自 _meta，缺失为 0）
 * @param sensitivity            敏感程度 0-1（personality_traits.sensitivity，可为 null）
 * @param sensitivityConfidence  sensitivity 的置信度
 * @param curiosity              好奇程度 0-1（personality_traits.curiosity，可为 null）
 * @param curiosityConfidence    curiosity 的置信度
 * @param copingSkills           已掌握技巧标签列表（如 "breathing_box", "grounding_54321"）
 * @param copingSkillsConfidence copingSkills 的置信度
 */
public record ProfileSignals(
        Double introversion,
        double introversionConfidence,
        List<String> dominantInterests,
        double interestsConfidence,
        Double sensitivity,
        double sensitivityConfidence,
        Double curiosity,
        double curiosityConfidence,
        List<String> copingSkills,
        double copingSkillsConfidence
) {

    /** 参与编排的最低置信度（evidence_count≥2 次 LLM 提炼后达到） */
    public static final double MIN_CONFIDENCE = 0.5;

    /** 向后兼容构造（P0 范围，无 P2 字段） */
    public ProfileSignals(Double introversion, double introversionConfidence,
                          List<String> dominantInterests, double interestsConfidence) {
        this(introversion, introversionConfidence, dominantInterests, interestsConfidence,
                null, 0, null, 0, null, 0);
    }

    /** introversion 是否可参与编排微调 */
    public boolean introversionUsable() {
        return introversion != null && introversionConfidence >= MIN_CONFIDENCE;
    }

    /** dominant_interests 是否可参与镜映取材 */
    public boolean interestsUsable() {
        return dominantInterests != null && !dominantInterests.isEmpty()
                && interestsConfidence >= MIN_CONFIDENCE;
    }

    /** ORCH-006：sensitivity 是否可参与追问强度调节 */
    public boolean sensitivityUsable() {
        return sensitivity != null && sensitivityConfidence >= MIN_CONFIDENCE;
    }

    /** ORCH-006：curiosity 是否可参与探索式引导决策 */
    public boolean curiosityUsable() {
        return curiosity != null && curiosityConfidence >= MIN_CONFIDENCE;
    }

    /** ORCH-006：copingSkills 是否可参与技能复用引导 */
    public boolean copingSkillsUsable() {
        return copingSkills != null && !copingSkills.isEmpty()
                && copingSkillsConfidence >= MIN_CONFIDENCE;
    }
}
