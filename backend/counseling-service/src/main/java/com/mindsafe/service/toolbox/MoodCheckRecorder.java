package com.mindsafe.service.toolbox;

import org.springframework.stereotype.Component;

/**
 * 工具练习情绪前后对比记录器（TOOL-001，design/36 §3.2 preMoodCheck/postMoodCheck）
 * <p>
 * 练习前后各一次 1-5 表情打分，差值记录为工具效果。
 * 效果数据为 S3 级（进画像 + design/39 实验指标）。
 * <p>
 * 纯函数实现：计算效果分 + 判定有效性。
 */
@Component
public class MoodCheckRecorder {

    /** 情绪打分范围 */
    public static final int MOOD_MIN = 1;
    public static final int MOOD_MAX = 5;

    /** 效果记录 */
    public record MoodEffect(
            String toolId,
            int preMood,
            int postMood,
            int delta,
            EffectLevel level
    ) {
    }

    /** 效果等级 */
    public enum EffectLevel {
        IMPROVED,     // 改善（delta > 0）
        UNCHANGED,    // 无变化（delta = 0）
        WORSENED,     // 恶化（delta < 0，需关注）
        INVALID       // 无效数据
    }

    /**
     * 记录并计算练习效果。
     *
     * @param toolId   工具 ID
     * @param preMood  练习前情绪分（1-5）
     * @param postMood 练习后情绪分（1-5）
     * @return 效果记录
     */
    public MoodEffect record(String toolId, int preMood, int postMood) {
        if (!isValidMood(preMood) || !isValidMood(postMood)) {
            return new MoodEffect(toolId, preMood, postMood, 0, EffectLevel.INVALID);
        }
        int delta = postMood - preMood;
        EffectLevel level = delta > 0 ? EffectLevel.IMPROVED
                : delta < 0 ? EffectLevel.WORSENED
                : EffectLevel.UNCHANGED;
        return new MoodEffect(toolId, preMood, postMood, delta, level);
    }

    /**
     * 判断效果是否值得记录（恶化需特别关注）。
     */
    public boolean needsAttention(MoodEffect effect) {
        return effect.level() == EffectLevel.WORSENED;
    }

    /**
     * 判断情绪分是否合法。
     */
    public boolean isValidMood(int mood) {
        return mood >= MOOD_MIN && mood <= MOOD_MAX;
    }
}
