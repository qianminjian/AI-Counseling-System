package com.mindsafe.domain.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 领域实体工厂方法单测（覆盖率补缺：counseling-domain 实体层）
 * <p>
 * 覆盖：全部 13 个带静态工厂方法的实体 + Notification 状态流转 + MessageSummary 截断。
 */
class DomainEntityFactoryTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    // ===== User =====

    @Test
    @DisplayName("createStudent 初始化学生字段")
    void createStudent_setsFields() {
        User u = User.createStudent(tenantId, userId, "小星星", "GRADE_6", "CLASS_1");
        assertEquals("student", u.getUserType());
        assertEquals("小星星", u.getPseudonym());
        assertEquals("GRADE_6", u.getGradeCode());
        assertEquals("CLASS_1", u.getClassCode());
        assertEquals("active", u.getStatus());
        assertNotNull(u.getCreatedAt());
    }

    @Test
    @DisplayName("createTeacher 初始化教师字段")
    void createTeacher_setsFields() {
        User u = User.createTeacher(tenantId, userId, "王老师", "psych_teacher");
        assertEquals("psych_teacher", u.getUserType());
        assertEquals("王老师", u.getPseudonym());
        assertEquals("active", u.getStatus());
    }

    // ===== RiskEvent =====

    @Test
    @DisplayName("fromDetection 初始化风险事件字段（source=session, status=open）")
    void fromDetection_setsFields() {
        UUID sessionId = UUID.randomUUID();
        RiskEvent e = RiskEvent.fromDetection(tenantId, userId, sessionId, "self_harm", 3);
        assertNotNull(e.getRiskEventId());
        assertEquals("session", e.getSourceType());
        assertEquals(sessionId, e.getSourceId());
        assertEquals("self_harm", e.getRiskType());
        assertEquals(3, e.getRiskLevel());
        assertEquals("keyword_agent", e.getDetectedBy());
        assertEquals("open", e.getStatus());
        assertNotNull(e.getDetectedAt());
    }

    // ===== Notification =====

    @Test
    @DisplayName("riskAlert 初始化通知字段（pending + risk_alert 模板）")
    void riskAlert_setsFields() {
        UUID riskEventId = UUID.randomUUID();
        Notification n = Notification.riskAlert(tenantId, userId, "psych_teacher", "标题", "摘要", riskEventId);
        assertNotNull(n.getNotificationId());
        assertEquals(userId, n.getRecipientUserId());
        assertEquals("psych_teacher", n.getRecipientRole());
        assertEquals("in_app", n.getChannel());
        assertEquals("risk_alert", n.getTemplateCode());
        assertEquals("risk_event", n.getRelatedType());
        assertEquals(riskEventId, n.getRelatedId());
        assertEquals("pending", n.getDeliveryStatus());
    }

    @Test
    @DisplayName("markSent / markRead 状态流转")
    void notification_stateTransitions() {
        Notification n = Notification.riskAlert(tenantId, userId, "psych_teacher", "t", "b", UUID.randomUUID());
        n.markSent();
        assertEquals("sent", n.getDeliveryStatus());
        assertNotNull(n.getSentAt());
        n.markRead();
        assertEquals("read", n.getDeliveryStatus());
        assertNotNull(n.getReadAt());
    }

    // ===== MessageSummary =====

    @Test
    @DisplayName("studentMessage 初始化摘要字段并生成 emotion/risk JSON")
    void studentMessage_setsFields() {
        MessageSummary m = MessageSummary.studentMessage(tenantId, UUID.randomUUID(), userId, 3, "内容", "焦虑", 2);
        assertEquals("student", m.getSenderType());
        assertEquals(3, m.getTurnCount());
        assertEquals("内容", m.getContentSummary());
        assertEquals("焦虑", m.getEmotionLabel());
        assertEquals(2, m.getRiskLevel());
        assertEquals("[\"焦虑\"]", m.getEmotionTags());
        assertEquals("[{\"level\":2}]", m.getRiskSignals());
    }

    @Test
    @DisplayName("studentMessage 超长内容截断至 1024，null 内容保留")
    void studentMessage_truncatesLongContent() {
        String longText = "x".repeat(2000);
        MessageSummary m = MessageSummary.studentMessage(tenantId, UUID.randomUUID(), userId, 1, longText, null, 0);
        assertEquals(1024, m.getContentSummary().length());
        assertEquals("[]", m.getEmotionTags());
        MessageSummary n = MessageSummary.studentMessage(tenantId, UUID.randomUUID(), userId, 1, null, null, 0);
        assertNull(n.getContentSummary());
    }

    @Test
    @DisplayName("aiMessage 初始化 AI 摘要字段（risk=0）")
    void aiMessage_setsFields() {
        MessageSummary m = MessageSummary.aiMessage(tenantId, UUID.randomUUID(), userId, 2, "AI 总结");
        assertEquals("ai", m.getSenderType());
        assertEquals(0, m.getRiskLevel());
        assertEquals("[]", m.getEmotionTags());
        assertEquals("[]", m.getRiskSignals());
    }

    // ===== CounselingSession =====

    @Test
    @DisplayName("create 初始化会话字段（默认 web 渠道 + active）")
    void session_create_setsFields() {
        CounselingSession s = CounselingSession.create(tenantId, userId, "开心", "web");
        assertNotNull(s.getSessionId());
        assertEquals("web", s.getChannel());
        assertEquals("text", s.getInteractionMode());
        assertEquals("active", s.getSessionStatus());
        assertEquals(0, s.getRiskLevelSnapshot());
        assertEquals("summary_only", s.getTranscriptPolicy());
        assertEquals("开心", s.getEmotionTag());
        assertNotNull(s.getStartedAt());
    }

    @Test
    @DisplayName("create null 渠道 → 默认 web")
    void session_create_defaultChannel() {
        CounselingSession s = CounselingSession.create(tenantId, userId, null, null);
        assertEquals("web", s.getChannel());
    }

    // ===== TeacherNote =====

    @Test
    @DisplayName("create 初始化备注字段，null 类型默认 general")
    void teacherNote_create_setsFields() {
        TeacherNote n = TeacherNote.create(tenantId, userId, UUID.randomUUID(), "内容", "case_stage");
        assertNotNull(n.getNoteId());
        assertEquals("case_stage", n.getNoteType());
        assertNotNull(n.getCreatedAt());
        TeacherNote n2 = TeacherNote.create(tenantId, userId, UUID.randomUUID(), "内容", null);
        assertEquals("general", n2.getNoteType());
    }

    // ===== AuditLog =====

    @Test
    @DisplayName("create 初始化审计日志字段")
    void auditLog_create_setsFields() {
        UUID resourceId = UUID.randomUUID();
        AuditLog log = AuditLog.create(tenantId, userId, "LOGIN", "user", resourceId, "detail");
        assertNotNull(log.getAuditLogId());
        assertEquals("LOGIN", log.getAction());
        assertEquals("user", log.getResourceType());
        assertEquals(resourceId, log.getResourceId());
        assertEquals("detail", log.getDetail());
        assertNotNull(log.getCreatedAt());
    }

    // ===== EmotionDiary =====

    @Test
    @DisplayName("create 初始化情绪日记字段")
    void emotionDiary_create_setsFields() {
        EmotionDiary d = EmotionDiary.create(tenantId, userId, "开心", 3, "备注");
        assertNotNull(d.getDiaryId());
        assertEquals("开心", d.getEmotionLabel());
        assertEquals(3, d.getIntensity());
        assertEquals("备注", d.getNote());
        assertNotNull(d.getDiaryDate());
    }

    // ===== LongTermMemory =====

    @Test
    @DisplayName("keyEvent 初始化关键事件记忆字段")
    void longTermMemory_keyEvent_setsFields() {
        LongTermMemory m = LongTermMemory.keyEvent(tenantId, userId, UUID.randomUUID(), "事件", "平静", 0.8f);
        assertNotNull(m.getMemoryId());
        assertEquals("key_event", m.getMemoryType());
        assertEquals("事件", m.getContent());
        assertEquals("平静", m.getEmotionContext());
        assertEquals(0.8f, m.getImportance());
        assertEquals(0, m.getRecallCount());
    }

    // ===== ModelCallLog =====

    @Test
    @DisplayName("create 初始化模型调用日志字段")
    void modelCallLog_create_setsFields() {
        ModelCallLog log = ModelCallLog.create(tenantId, UUID.randomUUID(), "risk_agent", "v1", "gpt-4o", 120, "ok");
        assertNotNull(log.getCallLogId());
        assertEquals("risk_agent", log.getAgentName());
        assertEquals("v1", log.getPromptVersion());
        assertEquals(120, log.getLatencyMs());
        assertEquals("ok", log.getStatus());
        assertEquals(0, log.getInputTokens());
    }

    // ===== PromptVersion =====

    @Test
    @DisplayName("create 初始化 Prompt 版本字段，null AB 组默认 control")
    void promptVersion_create_setsFields() {
        PromptVersion pv = PromptVersion.create(tenantId, "cbt_intake", 2, "内容", "描述", null, userId);
        assertNotNull(pv.getVersionId());
        assertEquals("cbt_intake", pv.getTemplateKey());
        assertEquals(2, pv.getVersion());
        assertEquals("control", pv.getAbGroup());
        assertFalse(pv.getIsActive());
        assertEquals(userId, pv.getCreatedBy());
        PromptVersion pv2 = PromptVersion.create(tenantId, "k", 1, "c", "d", "B", userId);
        assertEquals("B", pv2.getAbGroup());
    }

    // ===== ConsentRecord =====

    @Test
    @DisplayName("create 初始化同意记录字段")
    void consentRecord_create_setsFields() {
        ConsentRecord r = ConsentRecord.create(userId, tenantId, "guardian", "v1.0");
        assertNotNull(r.getConsentId());
        assertEquals(userId, r.getUserId());
        assertEquals(tenantId, r.getTenantId());
        assertEquals("guardian", r.getConsentType());
        assertEquals("v1.0", r.getConsentVersion());
        assertNotNull(r.getConsentedAt());
    }

    // ===== RelaxationSession =====

    @Test
    @DisplayName("create 初始化放松会话字段")
    void relaxationSession_create_setsFields() {
        RelaxationSession r = RelaxationSession.create(tenantId, userId, "breathing", 300, true);
        assertNotNull(r.getRelaxationId());
        assertEquals("breathing", r.getExerciseType());
        assertEquals(300, r.getDurationSeconds());
        assertTrue(r.getCompleted());
        assertNotNull(r.getCreatedAt());
    }

    // ===== setter 覆盖（随机属性读写） =====

    @Test
    @DisplayName("实体 setter/getter 读写一致（RiskEvent 代表性覆盖）")
    void riskEvent_settersRoundTrip() {
        RiskEvent e = RiskEvent.fromDetection(tenantId, userId, UUID.randomUUID(), "self_harm", 1);
        UUID assigned = UUID.randomUUID();
        Instant now = Instant.now();
        e.setAssignedUserId(assigned);
        e.setStatus("processing");
        e.setClosedAt(now);
        e.setResolutionNote("已干预");
        e.setResolvedAt(now);
        e.setFollowUpAt(now);
        assertEquals(assigned, e.getAssignedUserId());
        assertEquals("processing", e.getStatus());
        assertEquals(now, e.getClosedAt());
        assertEquals("已干预", e.getResolutionNote());
        assertEquals(now, e.getResolvedAt());
        assertEquals(now, e.getFollowUpAt());
    }
}
