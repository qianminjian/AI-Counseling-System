package com.mindsafe.service.teacher;

import org.springframework.stereotype.Component;

/**
 * 预警待办静音规则（F-3，design/35 §4.2 降噪机制第 3 条）
 * <p>
 * "已在个案跟踪中"的学生：S2/S3 新预警只进时间线不进待办；
 * S0/S1 永远进待办，不可静音（安全兜底铁律，与 AlertSlaPolicy 一致）。
 * <p>
 * riskLevel 数值约定（{@code RiskLevel} 枚举）：S0=RED(3)，S1=ORANGE(2)，S2=YELLOW(1)，S3=GREEN(0)。
 */
@Component
public class AlertTodoMutePolicy {

    /** S2/YELLOW 及以下视为低级别预警（可静音范围） */
    private static final int MAX_MUTABLE_LEVEL = 1;

    /**
     * 判断一条预警是否应从待办区静音（仍进时间线）。
     *
     * @param riskLevel      风险等级数值（null 视为不可静音，安全侧保守）
     * @param inCaseTracking 该学生是否已在个案跟踪中
     * @return true=只进时间线不进待办
     */
    public boolean isMutedFromTodo(Integer riskLevel, boolean inCaseTracking) {
        return inCaseTracking && riskLevel != null && riskLevel <= MAX_MUTABLE_LEVEL;
    }
}
