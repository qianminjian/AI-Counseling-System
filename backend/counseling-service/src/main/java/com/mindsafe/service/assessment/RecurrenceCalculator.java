package com.mindsafe.service.assessment;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 量表复测周期计算器（SCALE-002，design/34 §7.2 MBC 复测调度）
 * <p>
 * MBC（测量导向照护）核心：个案跟踪学生按 recurrence 周期自动复测。
 * <ul>
 *   <li>默认复测间隔：14 天（教师可改 7-28 天）</li>
 *   <li>普测：每学期 1 次（不走 recurrence，走学期日历）</li>
 *   <li>个案结案时自动终止复测</li>
 * </ul>
 * <p>
 * 纯函数实现，不依赖 DB/调度框架。接线时由 Spring @Scheduled 或 Quartz 驱动。
 * <p>
 * AUD-063（2026-08-06）：显式冻结——当前零生产调用（仅测试引用），
 * 待量表施测接线（frozen/59）时恢复；与同包 AssessmentScoringEngine/BuiltinScales/
 * ScoringResult 同属「保留·暂缓」登记项，冻结期间禁止删除或新增调用。
 */
@Component
public class RecurrenceCalculator {

    /** 默认复测间隔（天） */
    public static final int DEFAULT_INTERVAL_DAYS = 14;

    /** 允许的最小间隔（天） */
    public static final int MIN_INTERVAL_DAYS = 7;

    /** 允许的最大间隔（天） */
    public static final int MAX_INTERVAL_DAYS = 28;

    /** 任务类型 */
    public enum TaskType {
        SCREENING,  // 普测（学期 1 次）
        FOLLOWUP,   // 个案复测（recurrence 驱动）
        ADHOC       // 临时（教师手动）
    }

    /** 复测配置 */
    public record RecurrenceConfig(
            int intervalDays,
            String until  // "case_closed" / "term_end" / ISO date
    ) {
        public static RecurrenceConfig defaultFollowup() {
            return new RecurrenceConfig(DEFAULT_INTERVAL_DAYS, "case_closed");
        }
    }

    /**
     * 计算下次复测到期时间。
     *
     * @param lastAssessmentAt 上次施测完成时间
     * @param config           复测配置
     * @return 下次到期时间
     */
    public Instant computeNextDueDate(Instant lastAssessmentAt, RecurrenceConfig config) {
        int interval = clampInterval(config.intervalDays());
        return lastAssessmentAt.plus(interval, ChronoUnit.DAYS);
    }

    /**
     * 判断复测是否到期。
     *
     * @param lastAssessmentAt 上次施测完成时间
     * @param config           复测配置
     * @param now              当前时间
     * @return true=已到期或超期
     */
    public boolean isDue(Instant lastAssessmentAt, RecurrenceConfig config, Instant now) {
        if (lastAssessmentAt == null) return true; // 从未施测 → 立即到期
        Instant dueDate = computeNextDueDate(lastAssessmentAt, config);
        return !now.isBefore(dueDate);
    }

    /**
     * 计算超期天数（正值=超期，负值=未到期）。
     */
    public long overdueDays(Instant lastAssessmentAt, RecurrenceConfig config, Instant now) {
        if (lastAssessmentAt == null) return Long.MAX_VALUE;
        Instant dueDate = computeNextDueDate(lastAssessmentAt, config);
        return ChronoUnit.DAYS.between(dueDate, now);
    }

    /**
     * 判断复测是否应终止（个案结案 / 学期结束）。
     *
     * @param config      复测配置
     * @param caseClosed  个案是否已结案
     * @param termEndDate 学期结束日期（可为 null）
     * @param now         当前时间
     * @return true=应终止
     */
    public boolean shouldTerminate(RecurrenceConfig config, boolean caseClosed,
                                   Instant termEndDate, Instant now) {
        if ("case_closed".equals(config.until()) && caseClosed) return true;
        if ("term_end".equals(config.until()) && termEndDate != null && now.isAfter(termEndDate)) return true;
        // until 是 ISO date
        if (config.until() != null && !config.until().isBlank()
                && !config.until().equals("case_closed") && !config.until().equals("term_end")) {
            try {
                Instant untilDate = Instant.parse(config.until());
                return now.isAfter(untilDate);
            } catch (Exception ignored) {
                // 解析失败不终止（安全降级）
            }
        }
        return false;
    }

    /**
     * 校验并钳制间隔到合法范围。
     */
    public int clampInterval(int intervalDays) {
        return Math.max(MIN_INTERVAL_DAYS, Math.min(MAX_INTERVAL_DAYS, intervalDays));
    }
}
