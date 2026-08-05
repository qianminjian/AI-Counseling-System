package com.mindsafe.domain.entity;

import com.mindsafe.common.enums.RiskLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C2（2026-08-05）：状态魔法值收敛为实体常量，锁定值语义防漂移。
 * <p>
 * 背景：risk_events.status="open"、counseling_sessions.session_status="completed"、
 * users.status="active" 在多个 service 中散落硬编码，同一语义多处重复（DRY 违背）。
 */
class EntityStatusConstantsTest {

    @Test
    @DisplayName("RiskEvent 状态常量：open 对应风险事件待处理")
    void riskEventStatusOpen() {
        assertThat(RiskEvent.STATUS_OPEN).isEqualTo("open");
    }

    @Test
    @DisplayName("CounselingSession 状态常量：completed 对应会话已完成")
    void sessionStatusCompleted() {
        assertThat(CounselingSession.STATUS_COMPLETED).isEqualTo("completed");
    }

    @Test
    @DisplayName("User 状态常量：active 对应用户启用")
    void userStatusActive() {
        assertThat(User.STATUS_ACTIVE).isEqualTo("active");
    }

    @Test
    @DisplayName("User 状态常量：suspended 对应用户停用（租户暂停时批量下架）")
    void userStatusSuspended() {
        assertThat(User.STATUS_SUSPENDED).isEqualTo("suspended");
    }

    @Test
    @DisplayName("User 状态常量：pending 对应用户待启用（老师后台审批/初始化）")
    void userStatusPending() {
        assertThat(User.STATUS_PENDING).isEqualTo("pending");
    }

    @Test
    @DisplayName("User 类型常量：student 对应学生")
    void userTypeStudent() {
        assertThat(User.USER_TYPE_STUDENT).isEqualTo("student");
    }

    @Test
    @DisplayName("User 类型常量：teacher 对应老师")
    void userTypeTeacher() {
        assertThat(User.USER_TYPE_TEACHER).isEqualTo("teacher");
    }

    @Test
    @DisplayName("User 类型常量：psych_teacher 对应心理老师")
    void userTypePsychTeacher() {
        assertThat(User.USER_TYPE_PSYCH_TEACHER).isEqualTo("psych_teacher");
    }

    @Test
    @DisplayName("User 类型常量：class_teacher 对应班主任")
    void userTypeClassTeacher() {
        assertThat(User.USER_TYPE_CLASS_TEACHER).isEqualTo("class_teacher");
    }

    @Test
    @DisplayName("User 类型常量：admin 对应管理员")
    void userTypeAdmin() {
        assertThat(User.USER_TYPE_ADMIN).isEqualTo("admin");
    }

    @Test
    @DisplayName("RiskEvent 状态常量：claimed 对应预警已认领")
    void riskEventStatusClaimed() {
        assertThat(RiskEvent.STATUS_CLAIMED).isEqualTo("claimed");
    }

    @Test
    @DisplayName("RiskEvent 状态常量：closed 对应预警已闭环")
    void riskEventStatusClosed() {
        assertThat(RiskEvent.STATUS_CLOSED).isEqualTo("closed");
    }

    @Test
    @DisplayName("RiskEvent 状态常量：resolved 对应预警已解决（闭环统计口径）")
    void riskEventStatusResolved() {
        assertThat(RiskEvent.STATUS_RESOLVED).isEqualTo("resolved");
    }

    @Test
    @DisplayName("CounselingSession 状态常量：active 对应会话进行中")
    void sessionStatusActive() {
        assertThat(CounselingSession.STATUS_ACTIVE).isEqualTo("active");
    }

    @Test
    @DisplayName("Tenant 状态常量：active 对应租户正常")
    void tenantStatusActive() {
        assertThat(Tenant.STATUS_ACTIVE).isEqualTo("active");
    }

    @Test
    @DisplayName("Tenant 状态常量：suspended 对应租户暂停（禁止登录）")
    void tenantStatusSuspended() {
        assertThat(Tenant.STATUS_SUSPENDED).isEqualTo("suspended");
    }

    @Test
    @DisplayName("School 状态常量：active 对应学校正常")
    void schoolStatusActive() {
        assertThat(School.STATUS_ACTIVE).isEqualTo("active");
    }

    @Test
    @DisplayName("ParentAccount 状态常量：active 对应家长账号正常")
    void parentAccountStatusActive() {
        assertThat(ParentAccount.STATUS_ACTIVE).isEqualTo("active");
    }

    @Test
    @DisplayName("TrialInviteCode 状态常量：active 对应邀请码有效")
    void trialInviteCodeStatusActive() {
        assertThat(TrialInviteCode.STATUS_ACTIVE).isEqualTo("active");
    }

    @Test
    @DisplayName("RiskLevel 枚举 severity 锁定：YELLOW=1（风险事件持久化依赖该数值）")
    void riskLevelSeverityLocked() {
        assertThat(RiskLevel.YELLOW.severity()).isEqualTo(1);
    }
}
