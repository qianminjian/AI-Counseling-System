package com.mindsafe.service.assessment;

import com.mindsafe.service.assessment.RecurrenceCalculator.RecurrenceConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCALE-002 复测 recurrence 计算器 单元测试
 */
class RecurrenceCalculatorTest {

    private final RecurrenceCalculator calc = new RecurrenceCalculator();
    private final Instant now = Instant.parse("2026-07-28T10:00:00Z");

    @Test
    @DisplayName("默认间隔 14 天：上次 + 14 = 到期")
    void defaultInterval() {
        Instant last = now.minus(14, ChronoUnit.DAYS);
        RecurrenceConfig config = RecurrenceConfig.defaultFollowup();
        assertThat(calc.computeNextDueDate(last, config)).isEqualTo(last.plus(14, ChronoUnit.DAYS));
        assertThat(calc.isDue(last, config, now)).isTrue();
    }

    @Test
    @DisplayName("未到期：上次 + 10 天（< 14）")
    void notDue() {
        Instant last = now.minus(10, ChronoUnit.DAYS);
        RecurrenceConfig config = RecurrenceConfig.defaultFollowup();
        assertThat(calc.isDue(last, config, now)).isFalse();
        assertThat(calc.overdueDays(last, config, now)).isEqualTo(-4);
    }

    @Test
    @DisplayName("超期 3 天")
    void overdue() {
        Instant last = now.minus(17, ChronoUnit.DAYS);
        RecurrenceConfig config = RecurrenceConfig.defaultFollowup();
        assertThat(calc.isDue(last, config, now)).isTrue();
        assertThat(calc.overdueDays(last, config, now)).isEqualTo(3);
    }

    @Test
    @DisplayName("从未施测 → 立即到期")
    void neverAssessed() {
        RecurrenceConfig config = RecurrenceConfig.defaultFollowup();
        assertThat(calc.isDue(null, config, now)).isTrue();
        assertThat(calc.overdueDays(null, config, now)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    @DisplayName("间隔钳制：<7 → 7，>28 → 28")
    void clampInterval() {
        assertThat(calc.clampInterval(3)).isEqualTo(7);
        assertThat(calc.clampInterval(7)).isEqualTo(7);
        assertThat(calc.clampInterval(14)).isEqualTo(14);
        assertThat(calc.clampInterval(28)).isEqualTo(28);
        assertThat(calc.clampInterval(60)).isEqualTo(28);
    }

    @Test
    @DisplayName("自定义间隔 21 天")
    void customInterval() {
        Instant last = now.minus(21, ChronoUnit.DAYS);
        RecurrenceConfig config = new RecurrenceConfig(21, "case_closed");
        assertThat(calc.isDue(last, config, now)).isTrue();
        assertThat(calc.isDue(last.plus(1, ChronoUnit.DAYS), config, now)).isFalse();
    }

    @Test
    @DisplayName("终止：个案结案 + until=case_closed")
    void terminate_caseClosed() {
        RecurrenceConfig config = RecurrenceConfig.defaultFollowup();
        assertThat(calc.shouldTerminate(config, true, null, now)).isTrue();
        assertThat(calc.shouldTerminate(config, false, null, now)).isFalse();
    }

    @Test
    @DisplayName("终止：学期结束 + until=term_end")
    void terminate_termEnd() {
        RecurrenceConfig config = new RecurrenceConfig(14, "term_end");
        Instant termEnd = now.minus(1, ChronoUnit.DAYS);
        assertThat(calc.shouldTerminate(config, false, termEnd, now)).isTrue();
        assertThat(calc.shouldTerminate(config, false, now.plus(30, ChronoUnit.DAYS), now)).isFalse();
    }

    @Test
    @DisplayName("终止：ISO date until")
    void terminate_isoDate() {
        RecurrenceConfig config = new RecurrenceConfig(14, "2026-07-01T00:00:00Z");
        assertThat(calc.shouldTerminate(config, false, null, now)).isTrue();

        RecurrenceConfig future = new RecurrenceConfig(14, "2026-12-31T00:00:00Z");
        assertThat(calc.shouldTerminate(future, false, null, now)).isFalse();
    }

    @Test
    @DisplayName("终止：无效 until 不终止（安全降级）")
    void terminate_invalidUntil() {
        RecurrenceConfig config = new RecurrenceConfig(14, "not_a_date");
        assertThat(calc.shouldTerminate(config, false, null, now)).isFalse();
    }

    @Test
    @DisplayName("边界：恰好到期时刻 = 到期")
    void exactDueMoment() {
        Instant last = now.minus(14, ChronoUnit.DAYS);
        RecurrenceConfig config = RecurrenceConfig.defaultFollowup();
        Instant dueDate = calc.computeNextDueDate(last, config);
        assertThat(calc.isDue(last, config, dueDate)).isTrue();
        assertThat(calc.isDue(last, config, dueDate.minus(1, ChronoUnit.SECONDS))).isFalse();
    }
}
