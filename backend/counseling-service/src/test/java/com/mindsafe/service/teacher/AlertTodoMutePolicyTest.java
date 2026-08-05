package com.mindsafe.service.teacher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AlertTodoMutePolicy 单测（F-3，design/35 §4.2 降噪机制第 3 条）
 * <p>
 * 规则："已在个案跟踪中"学生的 S2/S3 新预警只进时间线不进待办；
 * S0/S1 永远进待办，不可静音（安全兜底铁律）。
 * <p>
 * riskLevel 数值约定（RiskLevel 枚举）：S0=RED(3)，S1=ORANGE(2)，S2=YELLOW(1)，S3=GREEN(0)。
 */
class AlertTodoMutePolicyTest {

    private final AlertTodoMutePolicy policy = new AlertTodoMutePolicy();

    @Test
    void 个案跟踪中的学生_S2预警_静音() {
        assertTrue(policy.isMutedFromTodo(1, true));
    }

    @Test
    void 个案跟踪中的学生_S3预警_静音() {
        assertTrue(policy.isMutedFromTodo(0, true));
    }

    @Test
    void 个案跟踪中的学生_S1预警_不可静音() {
        assertFalse(policy.isMutedFromTodo(2, true));
    }

    @Test
    void 个案跟踪中的学生_S0预警_不可静音() {
        assertFalse(policy.isMutedFromTodo(3, true));
    }

    @Test
    void 未个案跟踪的学生_S2预警_不静音() {
        assertFalse(policy.isMutedFromTodo(1, false));
    }

    @Test
    void 风险等级缺失_不静音_安全侧保守() {
        assertFalse(policy.isMutedFromTodo(null, true));
    }
}
