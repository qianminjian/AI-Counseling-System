package com.mindsafe.api.render;

/**
 * 情绪码值 → 中文标签（F8：从 TeacherController 抽离）。
 * <p>
 * DC-008：EmotionVocabulary.ZH_LABELS 单一标签源（anxious→紧张 全系统单译）。
 * 本类作为 api 层对 ai 模块标签源的唯一委托点，controller 不再直接依赖 ai 包。
 */
public final class EmotionLabelSupport {

    private EmotionLabelSupport() {
    }

    /** 情绪码值 → 中文标签（未知码值返回原值，与 EmotionVocabulary.labelOf 语义一致） */
    public static String labelOf(String code) {
        return com.mindsafe.ai.risk.EmotionVocabulary.labelOf(code);
    }
}
