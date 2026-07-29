package com.mindsafe.service.quality;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 共情「命名-确认-容纳」结构评估器（EMP-201，design/52 §四，接 design/45 质量评估）
 * <p>
 * 循证共情（Rogers 无条件积极关注 + Affect Labeling）有明确三段式步骤：
 * <ol>
 *   <li><b>命名</b>（affect labeling）：准确说出孩子的情绪——"你看起来很难过"</li>
 *   <li><b>确认</b>（normalization）：正常化/确认——"有这种感觉很正常"</li>
 *   <li><b>容纳</b>（holding）：陪伴容纳而非急于解决——"我陪着你，慢慢说"</li>
 * </ol>
 * 纯 LLM 易滑向儿童最反感的<b>无效共情</b>：急于解决问题 / 说教 / 廉价安慰（"别难过啦"）。
 * 本评估器以规则化方式检测三段式命中与反模式，产出结构化共情维度分，
 * 作为 ConversationQualityService（LLM-as-Judge empathy_score）的确定性补充。
 * <p>
 * 纯函数实现，零 LLM 依赖。接线时由会话结束质量评估流程消费（接 PEVAL-001）。
 */
@Component
public class EmpathyStructureEvaluator {

    /** 每个反模式的结构分扣减 */
    public static final double ANTI_PATTERN_PENALTY = 0.2;

    /** 有效共情判定：至少命中的三段式步数 */
    public static final int EFFECTIVE_MIN_STEPS = 2;

    // ===== 三段式关键词库（儿童向中文） =====

    /** 情绪词库（命名对象） */
    private static final String[] EMOTION_WORDS = {
            "难过", "伤心", "生气", "愤怒", "害怕", "恐惧", "紧张", "担心", "担忧",
            "委屈", "孤单", "孤独", "失落", "焦虑", "不安", "沮丧", "烦躁", "郁闷",
            "开心", "高兴", "兴奋", "期待", "害羞", "尴尬", "嫉妒", "想念"
    };

    /** 命名框架（"你看起来/听起来…"——把情绪说出来） */
    private static final String[] NAMING_FRAMES = {
            "你看起来", "听起来", "你感到", "你觉得", "感受到", "是不是",
            "像是", "似乎", "你心里", "我能感受", "看得出来", "你有点"
    };

    /** 确认/正常化话术 */
    private static final String[] VALIDATION_PHRASES = {
            "很正常", "正常的", "换作", "很多人", "谁都会", "可以理解",
            "是可以的", "没关系", "并不奇怪", "都会这样", "不丢人", "不算什么错"
    };

    /** 容纳/陪伴话术 */
    private static final String[] CONTAINING_PHRASES = {
            "我陪", "陪着你", "我在这里", "我在你", "慢慢来", "不着急",
            "想说就", "我会一直", "在你身边", "陪你说", "我听着", "我陪你"
    };

    // ===== 无效共情反模式 =====

    /** 廉价安慰（否认情绪） */
    private static final String[] CHEAP_REASSURANCE = {
            "别难过", "别哭", "别伤心", "别想了", "没事的", "开心点",
            "别担心", "想开点", "没什么大不了", "别哭了", "别害怕"
    };

    /** 说教（居高临下） */
    private static final String[] PREACHING = {
            "你应该", "你必须", "你不该", "你不可以", "要懂事", "要听话", "你要乖"
    };

    /** 急于解决（过早给方案） */
    private static final String[] RUSHING_TO_SOLVE = {
            "建议你", "不妨", "你可以去", "你去跟", "赶紧去", "你快去"
    };

    // ==================== 单轮评估 ====================

    /** 共情结构评估结果 */
    public record EmpathyAssessment(
            boolean namingPresent,
            boolean validationPresent,
            boolean containingPresent,
            int structureSteps,
            List<String> antiPatterns,
            double structureScore,
            boolean effectiveEmpathy
    ) {
    }

    /**
     * 评估单条 AI 回复的共情结构。
     *
     * @param aiResponse AI 回复文本
     * @return 共情结构评估
     */
    public EmpathyAssessment evaluate(String aiResponse) {
        if (aiResponse == null || aiResponse.isBlank()) {
            return new EmpathyAssessment(false, false, false, 0, List.of(), 0.0, false);
        }

        // 命名：情绪词 + 命名框架（把情绪准确说出来）
        boolean naming = containsAny(aiResponse, EMOTION_WORDS)
                && containsAny(aiResponse, NAMING_FRAMES);
        boolean validation = containsAny(aiResponse, VALIDATION_PHRASES);
        boolean containing = containsAny(aiResponse, CONTAINING_PHRASES);

        int steps = (naming ? 1 : 0) + (validation ? 1 : 0) + (containing ? 1 : 0);

        List<String> antiPatterns = detectAntiPatterns(aiResponse);

        double score = steps / 3.0 - antiPatterns.size() * ANTI_PATTERN_PENALTY;
        score = BigDecimal.valueOf(Math.max(0, score)).setScale(3, RoundingMode.HALF_UP).doubleValue();

        boolean effective = steps >= EFFECTIVE_MIN_STEPS && antiPatterns.isEmpty();

        return new EmpathyAssessment(naming, validation, containing, steps,
                antiPatterns, score, effective);
    }

    private List<String> detectAntiPatterns(String text) {
        List<String> hits = new ArrayList<>();
        if (containsAny(text, CHEAP_REASSURANCE)) hits.add("cheap_reassurance");
        if (containsAny(text, PREACHING)) hits.add("preaching");
        if (containsAny(text, RUSHING_TO_SOLVE)) hits.add("rushing_to_solve");
        return hits;
    }

    // ==================== 会话级聚合 ====================

    /** 会话共情维度汇总 */
    public record SessionEmpathySummary(
            int turnsEvaluated,
            double avgStructureScore,
            int effectiveTurns,
            int antiPatternTurns,
            double effectiveRatio
    ) {
    }

    /**
     * 汇总整段会话的共情结构维度（供 design/45 质量评估消费）。
     *
     * @param aiResponses 会话中 AI 回复列表
     * @return 会话共情汇总
     */
    public SessionEmpathySummary summarizeSession(List<String> aiResponses) {
        if (aiResponses == null || aiResponses.isEmpty()) {
            return new SessionEmpathySummary(0, 0.0, 0, 0, 0.0);
        }

        double scoreSum = 0;
        int effectiveTurns = 0;
        int antiPatternTurns = 0;

        for (String resp : aiResponses) {
            EmpathyAssessment a = evaluate(resp);
            scoreSum += a.structureScore();
            if (a.effectiveEmpathy()) effectiveTurns++;
            if (!a.antiPatterns().isEmpty()) antiPatternTurns++;
        }

        int n = aiResponses.size();
        double avg = BigDecimal.valueOf(scoreSum / n).setScale(3, RoundingMode.HALF_UP).doubleValue();
        double ratio = BigDecimal.valueOf((double) effectiveTurns / n)
                .setScale(3, RoundingMode.HALF_UP).doubleValue();

        return new SessionEmpathySummary(n, avg, effectiveTurns, antiPatternTurns, ratio);
    }

    private boolean containsAny(String text, String[] keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }
}
