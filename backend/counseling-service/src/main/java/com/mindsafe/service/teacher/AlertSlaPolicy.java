package com.mindsafe.service.teacher;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 预警 SLA 升级策略（WB-001，design/05 §13/§3.2，design/20 §3.2）
 * <p>
 * 安全兜底铁律：S0「5 分钟必须有人接住」。
 * <ul>
 *   <li>S0/RED：5 min 未认领 → 升级备份老师 + 通知管理者</li>
 *   <li>S1/ORANGE：15 min 未认领 → 升级</li>
 *   <li>S2/YELLOW：60 min 未认领 → 提醒（不升级）</li>
 *   <li>S3/GREEN：无 SLA</li>
 * </ul>
 * <p>
 * 纯函数实现。接线时由虚拟线程定时扫描 open/claimed 超时事件驱动。
 */
@Component
public class AlertSlaPolicy {

    /** SLA 超时阈值（分钟） */
    private static final int S0_SLA_MINUTES = 5;
    private static final int S1_SLA_MINUTES = 15;
    private static final int S2_SLA_MINUTES = 60;

    /** SLA 决策结果 */
    public record SlaDecision(
            boolean breached,
            boolean escalate,
            String action,
            long overdueMinutes
    ) {
    }

    /**
     * 评估预警事件的 SLA 状态。
     *
     * @param riskLevel 风险等级（S0/S1/S2/S3 或 RED/ORANGE/YELLOW/GREEN）
     * @param status    事件状态（open/claimed/resolved）
     * @param createdAt 事件创建时间
     * @param now       当前时间
     * @return SLA 决策
     */
    public SlaDecision evaluate(String riskLevel, String status, Instant createdAt, Instant now) {
        // 已解决的不评估
        if ("resolved".equals(status) || "closed".equals(status)) {
            return new SlaDecision(false, false, "NONE", 0);
        }

        int slaMinutes = getSlaMinutes(riskLevel);
        if (slaMinutes <= 0) {
            return new SlaDecision(false, false, "NONE", 0); // S3 无 SLA
        }

        long elapsedMinutes = Duration.between(createdAt, now).toMinutes();
        long overdue = elapsedMinutes - slaMinutes;

        if (overdue <= 0) {
            return new SlaDecision(false, false, "WITHIN_SLA", 0);
        }

        // 已超时
        boolean escalate = shouldEscalate(riskLevel, status);
        String action = escalate ? "ESCALATE" : "REMIND";
        return new SlaDecision(true, escalate, action, overdue);
    }

    /**
     * 获取 SLA 阈值（分钟）。
     */
    public int getSlaMinutes(String riskLevel) {
        return switch (riskLevel) {
            case "S0", "RED" -> S0_SLA_MINUTES;
            case "S1", "ORANGE" -> S1_SLA_MINUTES;
            case "S2", "YELLOW" -> S2_SLA_MINUTES;
            default -> 0; // S3/GREEN 无 SLA
        };
    }

    /**
     * 判断是否应升级（S0/S1 超时且未认领 → 升级；S2 仅提醒）。
     */
    private boolean shouldEscalate(String riskLevel, String status) {
        if ("S2".equals(riskLevel) || "YELLOW".equals(riskLevel)) return false;
        // S0/S1：open 状态超时 → 升级
        return "open".equals(status);
    }
}
