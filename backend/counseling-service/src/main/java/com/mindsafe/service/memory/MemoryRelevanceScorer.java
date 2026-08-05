package com.mindsafe.service.memory;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 记忆多因子相关性评分器（MEM-102，design/50 §4.2）
 * <p>
 * 升级现有 top5(importance+time) 为四因子加权召回：
 * <pre>
 * 召回分 = w1·relevance + w2·importance + w3·recency + w4·recurring_boost
 * </pre>
 * <p>
 * 纯函数实现，不依赖外部服务。向量相似度由调用方预计算后传入。
 */
@Component
public class MemoryRelevanceScorer {

    /** 权重配置（总和=1.0） */
    private static final double W_RELEVANCE = 0.35;
    private static final double W_IMPORTANCE = 0.25;
    private static final double W_RECENCY = 0.20;
    private static final double W_RECURRING = 0.20;

    /** 时效衰减半衰期（天）：14 天后权重减半 */
    private static final double HALF_LIFE_DAYS = 14.0;

    /** recurring_theme 类型加成（满分 1.0） */
    private static final double RECURRING_FULL_BOOST = 1.0;
    /** key_event 类型无 recurring 加成 */
    private static final double KEY_EVENT_BOOST = 0.0;

    /** C2：召回阈值（低于此值不注入记忆，宁缺毋滥） */
    public static final double RECALL_THRESHOLD = 0.3;

    /**
     * 计算单条记忆的综合召回分。
     *
     * @param vectorSimilarity 与当前语境的向量相似度（0~1，调用方预计算）
     * @param importance       记忆重要性（0~1）
     * @param createdAt        记忆创建时间
     * @param memoryType       记忆类型（key_event / recurring_theme）
     * @param now              当前时间
     * @return 综合召回分（0~1）
     */
    public double score(double vectorSimilarity, double importance,
                        Instant createdAt, String memoryType, Instant now) {
        double relevance = clamp01(vectorSimilarity);
        double imp = clamp01(importance);
        double recency = computeRecency(createdAt, now);
        double recurring = "recurring_theme".equals(memoryType) ? RECURRING_FULL_BOOST : KEY_EVENT_BOOST;

        return W_RELEVANCE * relevance
                + W_IMPORTANCE * imp
                + W_RECENCY * recency
                + W_RECURRING * recurring;
    }

    /**
     * 时效衰减（指数衰减，半衰期 14 天）。
     * <p>
     * 刚创建=1.0，14 天后=0.5，28 天后=0.25，永远>0。
     */
    public double computeRecency(Instant createdAt, Instant now) {
        if (createdAt == null || now == null) return 0.5;
        long daysOld = Duration.between(createdAt, now).toDays();
        if (daysOld <= 0) return 1.0;
        return Math.pow(0.5, daysOld / HALF_LIFE_DAYS);
    }

    /**
     * 判断记忆是否值得召回（低于阈值不注入，宁缺毋滥）。
     *
     * @param score 综合召回分
     * @return true=值得召回
     */
    public boolean isWorthRecalling(double score) {
        return score >= RECALL_THRESHOLD;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
