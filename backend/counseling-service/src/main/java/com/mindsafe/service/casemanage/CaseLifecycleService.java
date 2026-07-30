package com.mindsafe.service.casemanage;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 个案管理服务（WB-003，design/35 M3）
 * <p>
 * 落地 design/05 个案四阶段生命周期：建案 → 评估 → 干预跟踪 → 结案。
 * <ul>
 *   <li>阶段推进：每阶段有准入条件，不可跳级</li>
 *   <li>建案来源：预警转入 / 测评分档≥中度 / 手动创建</li>
 *   <li>随访调度：next_followup_at 到期进入今日待办</li>
 *   <li>结案：必填结案小结 + 终止复测调度</li>
 * </ul>
 * 纯函数实现。接线时由教师端 API + 定时任务消费。
 */
@Component
public class CaseLifecycleService {

    /** 个案阶段 */
    public enum CaseStage {
        INTAKE,           // 建案
        ASSESSMENT,       // 评估
        INTERVENTION,     // 干预跟踪
        CLOSED            // 结案
    }

    /** 建案来源 */
    public enum CaseSource {
        ALERT_TRANSFER,   // 预警转入
        SCALE_REFERRAL,   // 测评分档≥中度建议转入
        MANUAL            // 手动创建
    }

    /** 阶段推进结果 */
    public record StageTransition(
            boolean allowed,
            CaseStage from,
            CaseStage to,
            String reason
    ) {
    }

    /** 个案摘要 */
    public record CaseSummary(
            String caseId,
            String studentId,
            CaseStage stage,
            CaseSource source,
            Instant nextFollowupAt,
            Instant createdAt,
            String closingSummary
    ) {
    }

    /** 待办事项 */
    public record FollowupTodo(
            String caseId,
            String studentId,
            Instant dueAt,
            boolean overdue,
            long overdueMinutes
    ) {
    }

    /**
     * 判断阶段推进是否允许。
     * 规则：只能顺序推进，不可跳级；结案必须有小结。
     *
     * @param current        当前阶段
     * @param target         目标阶段
     * @param hasClosingSummary 是否已填写结案小结（仅结案时需要）
     * @return 推进结果
     */
    public StageTransition transition(CaseStage current, CaseStage target, boolean hasClosingSummary) {
        int currentIdx = current.ordinal();
        int targetIdx = target.ordinal();

        // 不可跳级
        if (targetIdx != currentIdx + 1) {
            return new StageTransition(false, current, target,
                    "不可跳级推进（当前 " + current + "，目标 " + target + "）");
        }

        // 结案必须有小结
        if (target == CaseStage.CLOSED && !hasClosingSummary) {
            return new StageTransition(false, current, target, "结案必须填写结案小结");
        }

        return new StageTransition(true, current, target, "推进至 " + target);
    }

    /**
     * 判断随访是否到期（进入今日待办）。
     *
     * @param nextFollowupAt 下次随访时间
     * @param now            当前时间
     * @return 待办事项，null=未到期
     */
    public FollowupTodo checkFollowupDue(String caseId, String studentId,
                                         Instant nextFollowupAt, Instant now) {
        if (nextFollowupAt == null) return null;

        // 到期窗口：当天或已过期
        if (nextFollowupAt.isAfter(now.plus(1, ChronoUnit.DAYS))) {
            return null; // 明天以后，不进入今日待办
        }

        boolean overdue = nextFollowupAt.isBefore(now);
        long overdueMinutes = overdue ? ChronoUnit.MINUTES.between(nextFollowupAt, now) : 0;

        return new FollowupTodo(caseId, studentId, nextFollowupAt, overdue, overdueMinutes);
    }

    /**
     * 判断测评分档是否建议转入个案。
     * 规则：中度及以上建议转入。
     *
     * @param severityLevel 严重程度（0=正常, 1=轻度, 2=中度, 3=重度）
     * @return true=建议建案
     */
    public boolean shouldReferToCase(int severityLevel) {
        return severityLevel >= 2;
    }

    /**
     * 结案时终止复测调度。
     *
     * @param stage 当前阶段
     * @return true=应终止复测
     */
    public boolean shouldTerminateRetest(CaseStage stage) {
        return stage == CaseStage.CLOSED;
    }

    /**
     * 批量检查个案列表的随访待办。
     *
     * @param cases 个案列表
     * @param now   当前时间
     * @return 到期/逾期待办列表
     */
    public List<FollowupTodo> getDueFollowups(List<CaseSummary> cases, Instant now) {
        if (cases == null) return List.of();
        return cases.stream()
                .filter(c -> c.stage() != CaseStage.CLOSED) // 结案不再生成待办
                .map(c -> checkFollowupDue(c.caseId(), c.studentId(), c.nextFollowupAt(), now))
                .filter(java.util.Objects::nonNull)
                .toList();
    }
}
