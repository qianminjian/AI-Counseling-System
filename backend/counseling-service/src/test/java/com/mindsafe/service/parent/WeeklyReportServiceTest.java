package com.mindsafe.service.parent;

import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.MessageSummary;
import com.mindsafe.domain.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * WeeklyReportService 家长周报单测：学生缺失返回 null、会话/情绪聚合、
 * 风险等级文案 switch（0/1/2/3）、AI 建议规则化拼接。
 */
@ExtendWith(MockitoExtension.class)
class WeeklyReportServiceTest {

    @Mock private ParentService parentService;

    private WeeklyReportService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID studentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new WeeklyReportService(parentService);
    }

    private User student() {
        User u = new User();
        u.setUserId(studentId);
        u.setTenantId(tenantId);
        u.setPseudonym("小明");
        u.setGradeCode("三年级");
        u.setClassCode("1班");
        return u;
    }

    private CounselingSession session(int risk, int turns) {
        CounselingSession s = new CounselingSession();
        s.setRiskLevelSnapshot(risk);
        s.setTurnCount(turns);
        s.setStartedAt(Instant.now());
        return s;
    }

    private MessageSummary message(String emotion) {
        MessageSummary m = new MessageSummary();
        m.setEmotionLabel(emotion);
        return m;
    }

    @Test
    @DisplayName("学生不存在 → 返回 null")
    void generate_studentNull() {
        when(parentService.getStudent(eq(tenantId), eq(studentId))).thenReturn(null);
        assertThat(service.generate(tenantId, studentId)).isNull();
    }

    @Test
    @DisplayName("风险 3：需关注 + 非良好情绪附加 + 有对话次数")
    void generate_risk3() {
        when(parentService.getStudent(eq(tenantId), eq(studentId))).thenReturn(student());
        when(parentService.getRecentSessions(eq(tenantId), eq(studentId), any(Instant.class)))
                .thenReturn(List.of(session(3, 12), session(1, 0)));
        when(parentService.getRecentStudentMessages(eq(tenantId), eq(studentId), any(Instant.class)))
                .thenReturn(List.of(message("angry"), message("angry"), message("happy")));

        Map<String, Object> report = service.generate(tenantId, studentId);

        assertThat(report).isNotNull();
        assertThat(report.get("studentNickname")).isEqualTo("小明");
        assertThat(report.get("sessionCount")).isEqualTo(2);
        assertThat(report.get("totalTurns")).isEqualTo(12);
        assertThat(report.get("maxRiskLevel")).isEqualTo(3);
        assertThat(report.get("riskLabel")).isEqualTo("需关注");
        assertThat((Map<String, Long>) report.get("emotionDistribution"))
                .containsEntry("angry", 2L).containsEntry("happy", 1L);
        String advice = (String) report.get("aiAdvice");
        assertThat(advice).contains("重点关注").contains("angry").contains("2 次倾诉");
    }

    @Test
    @DisplayName("风险 2：轻度波动文案")
    void generate_risk2() {
        when(parentService.getStudent(eq(tenantId), eq(studentId))).thenReturn(student());
        when(parentService.getRecentSessions(eq(tenantId), eq(studentId), any(Instant.class)))
                .thenReturn(List.of(session(2, 5)));
        when(parentService.getRecentStudentMessages(eq(tenantId), eq(studentId), any(Instant.class)))
                .thenReturn(List.of(message("sad")));

        Map<String, Object> report = service.generate(tenantId, studentId);

        assertThat(report.get("riskLabel")).isEqualTo("轻度波动");
        assertThat((String) report.get("aiAdvice")).contains("轻度波动");
    }

    @Test
    @DisplayName("风险 1：平稳文案")
    void generate_risk1() {
        when(parentService.getStudent(eq(tenantId), eq(studentId))).thenReturn(student());
        when(parentService.getRecentSessions(eq(tenantId), eq(studentId), any(Instant.class)))
                .thenReturn(List.of(session(1, 3)));
        when(parentService.getRecentStudentMessages(eq(tenantId), eq(studentId), any(Instant.class)))
                .thenReturn(List.of());

        Map<String, Object> report = service.generate(tenantId, studentId);

        assertThat(report.get("riskLabel")).isEqualTo("平稳");
        // 情绪分布为空 → 不附加情绪文案；有会话 → 有次数文案
        assertThat((String) report.get("aiAdvice")).contains("平稳").doesNotContain("表达最多的情绪").contains("1 次倾诉");
    }

    @Test
    @DisplayName("风险 0（无会话）：良好文案 + 鼓励对话")
    void generate_risk0_noSession() {
        when(parentService.getStudent(eq(tenantId), eq(studentId))).thenReturn(student());
        when(parentService.getRecentSessions(eq(tenantId), eq(studentId), any(Instant.class)))
                .thenReturn(List.of());
        when(parentService.getRecentStudentMessages(eq(tenantId), eq(studentId), any(Instant.class)))
                .thenReturn(List.of());

        Map<String, Object> report = service.generate(tenantId, studentId);

        assertThat(report.get("maxRiskLevel")).isEqualTo(0);
        assertThat(report.get("riskLabel")).isEqualTo("良好");
        assertThat((String) report.get("aiAdvice")).contains("情绪状态良好").contains("暂无对话记录");
    }

    @Test
    @DisplayName("情绪为 happy/calm 基线 → 不附加情绪文案；riskLevelSnapshot null 按 0 处理")
    void generate_baselineEmotion_nullRisk() {
        when(parentService.getStudent(eq(tenantId), eq(studentId))).thenReturn(student());
        when(parentService.getRecentSessions(eq(tenantId), eq(studentId), any(Instant.class)))
                .thenReturn(List.of(session(0, 1), session(0, 1)));
        when(parentService.getRecentStudentMessages(eq(tenantId), eq(studentId), any(Instant.class)))
                .thenReturn(List.of(message("happy"), message("calm")));

        Map<String, Object> report = service.generate(tenantId, studentId);

        assertThat((String) report.get("aiAdvice")).doesNotContain("表达最多的情绪");
        assertThat(report.get("totalTurns")).isEqualTo(2);
    }
}
