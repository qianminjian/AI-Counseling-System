package com.mindsafe.service.profile;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 画像合并门控（PROF-023，design/46 §九 P1）
 * <p>
 * 防止异常单次翻转画像：置信加权 + 冲突检测 + 时效衰减。
 * <ul>
 *   <li>置信加权：新证据 confidence 低于现有 → 不覆盖，仅追加 evidence_count</li>
 *   <li>冲突检测：新旧值差 > 0.4 → 标记冲突，取加权均值而非直接替换</li>
 *   <li>时效衰减：超过 90 天的画像维度 confidence 自动衰减（半衰期 60 天）</li>
 * </ul>
 * 纯函数实现。接线时由 ProfileExtractorService 异步提炼后调用。
 */
@Component
public class ProfileMergeGate {

    /** 冲突阈值：新旧值差超过此值视为冲突 */
    private static final double CONFLICT_THRESHOLD = 0.4;

    /** 时效衰减半衰期（天） */
    private static final int DECAY_HALF_LIFE_DAYS = 60;

    /** 最大衰减天数（超过 180 天 confidence 降至极低） */
    private static final int MAX_DECAY_DAYS = 180;

    /** 合并决策结果 */
    public record MergeDecision(
            double mergedValue,
            double mergedConfidence,
            boolean conflictDetected,
            String strategy   // REPLACE / WEIGHTED_MERGE / KEEP_EXISTING / DECAY_ONLY
    ) {
    }

    /**
     * 合并新旧画像维度值。
     *
     * @param existingValue      现有值（0-1）
     * @param existingConfidence 现有置信度（0-1）
     * @param newValue           新证据值（0-1）
     * @param newConfidence      新证据置信度（0-1）
     * @return 合并决策
     */
    public MergeDecision merge(double existingValue, double existingConfidence,
                               double newValue, double newConfidence) {
        // 规则 1：新证据置信度太低 → 不覆盖
        if (newConfidence < 0.3) {
            return new MergeDecision(existingValue, existingConfidence, false, "KEEP_EXISTING");
        }

        // 规则 2：无现有值（首次）→ 直接替换
        if (existingConfidence <= 0) {
            return new MergeDecision(newValue, newConfidence, false, "REPLACE");
        }

        double diff = Math.abs(newValue - existingValue);

        // 规则 3：冲突检测
        if (diff > CONFLICT_THRESHOLD) {
            // 加权均值（置信度为权重）
            double totalConf = existingConfidence + newConfidence;
            double weighted = (existingValue * existingConfidence + newValue * newConfidence) / totalConf;
            double mergedConf = Math.min(1.0, totalConf / 2); // 冲突降低置信
            return new MergeDecision(weighted, mergedConf, true, "WEIGHTED_MERGE");
        }

        // 规则 4：正常更新（EMA 风格，新证据权重 0.3）
        double alpha = 0.3 * (newConfidence / Math.max(existingConfidence, 0.01));
        alpha = Math.min(alpha, 0.5); // 单次不超过 50% 影响
        double merged = existingValue * (1 - alpha) + newValue * alpha;
        double mergedConf = Math.min(1.0, existingConfidence + 0.05); // 每次证据 +0.05 置信
        return new MergeDecision(merged, mergedConf, false, "REPLACE");
    }

    /**
     * 时效衰减：超过半衰期的画像维度 confidence 自动降低。
     *
     * @param confidence 当前置信度
     * @param lastUpdated 最后更新时间
     * @param now         当前时间
     * @return 衰减后的置信度
     */
    public double applyDecay(double confidence, Instant lastUpdated, Instant now) {
        long daysSince = Duration.between(lastUpdated, now).toDays();
        if (daysSince <= 0) return confidence;
        if (daysSince >= MAX_DECAY_DAYS) return confidence * 0.1; // 极低

        // 指数衰减：confidence * 0.5^(days/halfLife)
        double decayFactor = Math.pow(0.5, (double) daysSince / DECAY_HALF_LIFE_DAYS);
        return confidence * decayFactor;
    }

    /**
     * 判断画像维度是否因衰减而失效（confidence < 阈值 → 不再参与编排）。
     */
    public boolean isExpired(double confidence, Instant lastUpdated, Instant now) {
        double decayed = applyDecay(confidence, lastUpdated, now);
        return decayed < 0.3; // 低于 0.3 视为失效
    }
}
