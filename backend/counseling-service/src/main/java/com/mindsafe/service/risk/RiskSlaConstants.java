package com.mindsafe.service.risk;

import java.util.Map;

/**
 * SLA 处置阈值权威常量源（P1-4：RiskMetricsJob 与 RiskOverviewService 双处重复定义 → 收敛单源）。
 * <p>
 * 口径（design/doing/83 后台管理端 §8.3）：权威 RiskLevel RED=3 / ORANGE=2 / YELLOW=1 / GREEN=0
 * <ul>
 *   <li>3（RED）→ 15min</li>
 *   <li>2（ORANGE）→ 60min（1h）</li>
 *   <li>1（YELLOW）→ 480min（1 工作日 8h）</li>
 *   <li>0（GREEN）→ 1440min（1 天，超时口径宽松）</li>
 * </ul>
 * 改 SLA 只动此一处；null/未知等级回落 GREEN 口径。
 */
public final class RiskSlaConstants {

    /** SLA 处置阈值（分钟，等级 → 时限） */
    public static final Map<Integer, Long> SLA_DISPOSE_MINUTES = Map.of(
            3, 15L,    // RED（S0）处置 15min
            2, 60L,    // ORANGE 处置 1h
            1, 480L,   // YELLOW 处置 1 工作日（8h）
            0, 1440L   // GREEN 处置 1 天（超时口径宽松）
    );

    /** 未知等级默认处置时限（分钟）：按 GREEN 口径 1 天 */
    public static final long DEFAULT_SLA_MINUTES = 1440L;

    private RiskSlaConstants() {
    }

    /** 等级 → SLA 处置时限（分钟）；null/未知等级回落默认值 */
    public static long slaMinutesFor(Integer riskLevel) {
        return SLA_DISPOSE_MINUTES.getOrDefault(riskLevel == null ? 0 : riskLevel, DEFAULT_SLA_MINUTES);
    }
}
