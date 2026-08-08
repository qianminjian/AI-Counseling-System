package com.mindsafe.service.conversation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SessionState 行为单元测试。
 * <p>
 * 覆盖：A3（2026-08-05）personalInfo 容量上限（防会话状态无限膨胀——
 * Redis 每轮全量 JSON 序列化，Map 无界会线性放大存储与带宽成本）。
 */
class SessionStateTest {

    private SessionState newState() {
        return new SessionState(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "sad", "web", "male", null, 4);
    }

    @Test
    @DisplayName("A3: personalInfo 有容量上限——超限新 key 被拒绝，已有 key 仍可更新")
    void personalInfo_hasCapacityLimit() {
        SessionState s = newState();

        // 填满上限（当前固定 key：realName/age/grade/class 共 4 个，上限 20 留扩展余量）
        for (int i = 0; i < 20; i++) {
            s.updatePersonalInfo("key" + i, "v" + i);
        }

        // 超过上限的新 key 应被拒绝（Map 保持有界，序列化成本不随会话无限增长）
        s.updatePersonalInfo("overflow", "x");
        assertThat(s.getPersonalInfo())
                .as("超限新 key 应被拒绝，防止会话状态无限膨胀")
                .doesNotContainKey("overflow");
        assertThat(s.getPersonalInfo()).hasSize(20);

        // 已有 key 的更新不受限（覆盖写不增加条目，不膨胀）
        s.updatePersonalInfo("key0", "updated");
        assertThat(s.getPersonalInfo().get("key0")).isEqualTo("updated");
        assertThat(s.getPersonalInfo()).hasSize(20);
    }

    @Test
    @DisplayName("空值/空白 value 不写入 personalInfo")
    void personalInfo_ignoresBlankValues() {
        SessionState s = newState();

        s.updatePersonalInfo("realName", null);
        s.updatePersonalInfo("age", "  ");
        assertThat(s.getPersonalInfo()).isEmpty();

        s.updatePersonalInfo("realName", "小波");
        assertThat(s.getPersonalInfo().get("realName")).isEqualTo("小波");
    }

    @Test
    @DisplayName("B2: canNudge 阈值由调用方传入——次数超限/间隔不足拦截，重置后可放行")
    void canNudge_usesInjectedThresholds() {
        SessionState s = newState();
        assertThat(s.canNudge(2, 20)).as("初始未 nudge 过 → 放行").isTrue();

        s.markNudged();
        s.markNudged();
        assertThat(s.canNudge(2, 20)).as("连续 nudge 达 maxCount=2 → 拦截").isFalse();
        assertThat(s.canNudge(5, 20))
                .as("放宽 maxCount=5 → 次数未超限，但距上次 nudge 不足 20s → 仍拦截").isFalse();

        s.setLastNudgeAt(Instant.now().minusSeconds(21));
        assertThat(s.canNudge(5, 20)).as("距上次 nudge 已超 20s → 放行").isTrue();

        s.setLastNudgeAt(Instant.now().minusSeconds(19));
        assertThat(s.canNudge(5, 20)).as("距上次 nudge 不足 20s → 拦截").isFalse();
    }
}
