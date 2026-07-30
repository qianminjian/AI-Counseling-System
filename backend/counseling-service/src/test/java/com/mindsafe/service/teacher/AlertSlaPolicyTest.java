package com.mindsafe.service.teacher;

import com.mindsafe.service.teacher.AlertSlaPolicy.SlaDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WB-001 预警 SLA 升级策略 单元测试
 */
class AlertSlaPolicyTest {

    private final AlertSlaPolicy policy = new AlertSlaPolicy();
    private final Instant now = Instant.parse("2026-07-28T10:00:00Z");

    @Test
    @DisplayName("S0 超 5 分钟 open → 升级")
    void s0_breach_escalate() {
        Instant created = now.minus(6, ChronoUnit.MINUTES);
        SlaDecision d = policy.evaluate("S0", "open", created, now);
        assertThat(d.breached()).isTrue();
        assertThat(d.escalate()).isTrue();
        assertThat(d.action()).isEqualTo("ESCALATE");
        assertThat(d.overdueMinutes()).isEqualTo(1);
    }

    @Test
    @DisplayName("S0 4 分钟 → 未超时")
    void s0_within() {
        Instant created = now.minus(4, ChronoUnit.MINUTES);
        SlaDecision d = policy.evaluate("S0", "open", created, now);
        assertThat(d.breached()).isFalse();
        assertThat(d.action()).isEqualTo("WITHIN_SLA");
    }

    @Test
    @DisplayName("S1 超 15 分钟 open → 升级")
    void s1_breach() {
        Instant created = now.minus(20, ChronoUnit.MINUTES);
        SlaDecision d = policy.evaluate("S1", "open", created, now);
        assertThat(d.breached()).isTrue();
        assertThat(d.escalate()).isTrue();
        assertThat(d.overdueMinutes()).isEqualTo(5);
    }

    @Test
    @DisplayName("S2 超 60 分钟 → 提醒不升级")
    void s2_remind_only() {
        Instant created = now.minus(90, ChronoUnit.MINUTES);
        SlaDecision d = policy.evaluate("S2", "open", created, now);
        assertThat(d.breached()).isTrue();
        assertThat(d.escalate()).isFalse();
        assertThat(d.action()).isEqualTo("REMIND");
    }

    @Test
    @DisplayName("S3 无 SLA")
    void s3_no_sla() {
        Instant created = now.minus(120, ChronoUnit.MINUTES);
        SlaDecision d = policy.evaluate("S3", "open", created, now);
        assertThat(d.breached()).isFalse();
        assertThat(d.action()).isEqualTo("NONE");
    }

    @Test
    @DisplayName("已解决 → 不评估")
    void resolved_skip() {
        Instant created = now.minus(60, ChronoUnit.MINUTES);
        SlaDecision d = policy.evaluate("S0", "resolved", created, now);
        assertThat(d.breached()).isFalse();
        assertThat(d.action()).isEqualTo("NONE");
    }

    @Test
    @DisplayName("S0 claimed 超时 → 升级（认领了但没处理完也升级）")
    void s0_claimed_breach() {
        Instant created = now.minus(10, ChronoUnit.MINUTES);
        SlaDecision d = policy.evaluate("S0", "claimed", created, now);
        assertThat(d.breached()).isTrue();
        // claimed 不是 open，shouldEscalate 返回 false
        assertThat(d.escalate()).isFalse();
        assertThat(d.action()).isEqualTo("REMIND");
    }

    @Test
    @DisplayName("SLA 阈值：S0=5, S1=15, S2=60, S3=0")
    void thresholds() {
        assertThat(policy.getSlaMinutes("S0")).isEqualTo(5);
        assertThat(policy.getSlaMinutes("RED")).isEqualTo(5);
        assertThat(policy.getSlaMinutes("S1")).isEqualTo(15);
        assertThat(policy.getSlaMinutes("S2")).isEqualTo(60);
        assertThat(policy.getSlaMinutes("S3")).isEqualTo(0);
        assertThat(policy.getSlaMinutes("GREEN")).isEqualTo(0);
    }
}
