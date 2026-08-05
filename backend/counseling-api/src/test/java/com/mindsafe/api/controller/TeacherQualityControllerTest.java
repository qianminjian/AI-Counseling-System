package com.mindsafe.api.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.MessageSummary;
import com.mindsafe.domain.entity.QualityScore;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.domain.mapper.MessageSummaryMapper;
import com.mindsafe.domain.mapper.QualityScoreMapper;
import com.mindsafe.domain.mapper.UserMapper;
import com.mindsafe.service.audit.AuditLogService;
import com.mindsafe.service.security.FieldEncryptionService;
import com.mindsafe.service.teacher.TeacherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.type.ObjectTypeHandler;
import org.springframework.security.core.Authentication;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TeacherQualityController 单元测试（P1 覆盖率冲刺：低分列表 / 概览指标 / 评分列表 / AI 统计 / 回放）
 * <p>
 * 覆盖：
 * - getFlaggedSessions 低分会话映射
 * - getQualityStats flagRate 计算 + totalRated=0 兜底
 * - getQualityScores 过滤 / 学生筛选空结果 / 学生名 enrich / 空值兜底
 * - getAiQualityStats 空数据与均值计算
 * - replaySession 跨租户拒绝、消息解密、评分叠加、学生名兜底
 */
class TeacherQualityControllerTest {

    private QualityScoreMapper qualityScoreMapper;
    private CounselingSessionMapper sessionMapper;
    private UserMapper userMapper;
    private MessageSummaryMapper messageSummaryMapper;
    private AuditLogService auditLogService;
    private FieldEncryptionService fieldEncryptionService;
    private TeacherService teacherService;
    private TeacherQualityController controller;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID teacherUserId = UUID.randomUUID();
    private final UUID studentUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // 纯 mock 测试无 Spring 容器，手动注册 MyBatis-Plus 表信息供 LambdaQueryWrapper 解析列名
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.getTypeHandlerRegistry().register(UUID.class, ObjectTypeHandler.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""),
                CounselingSession.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""),
                QualityScore.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""),
                MessageSummary.class);
        qualityScoreMapper = mock(QualityScoreMapper.class);
        sessionMapper = mock(CounselingSessionMapper.class);
        userMapper = mock(UserMapper.class);
        messageSummaryMapper = mock(MessageSummaryMapper.class);
        auditLogService = mock(AuditLogService.class);
        fieldEncryptionService = mock(FieldEncryptionService.class);
        teacherService = mock(TeacherService.class);
        controller = new TeacherQualityController(qualityScoreMapper, sessionMapper, userMapper,
                messageSummaryMapper, auditLogService, fieldEncryptionService, teacherService);
    }

    private Authentication teacherAuth() {
        Authentication a = mock(Authentication.class);
        when(a.getPrincipal()).thenReturn(teacherUserId);
        when(a.getDetails()).thenReturn(new TenantContext(tenantId, teacherUserId, "psych_teacher"));
        return a;
    }

    private CounselingSession session(Integer rating, UUID studentId) {
        CounselingSession s = new CounselingSession();
        s.setSessionId(UUID.randomUUID());
        s.setTenantId(tenantId);
        s.setStudentUserId(studentId);
        s.setSatisfactionRating(rating);
        s.setSatisfactionComment("需要关注");
        s.setStartedAt(Instant.now());
        s.setSessionStatus("completed");
        s.setTurnCount(12);
        s.setSessionSummary("摘要");
        return s;
    }

    private QualityScore score() {
        QualityScore q = new QualityScore();
        q.setScoreId(UUID.randomUUID());
        q.setTenantId(tenantId);
        q.setSessionId(UUID.randomUUID());
        q.setEmpathyScore(new BigDecimal("80"));
        q.setCbtCompletion(new BigDecimal("70"));
        q.setSafetyCompliance(new BigDecimal("90"));
        q.setEngagementScore(new BigDecimal("60"));
        q.setOverallScore(new BigDecimal("75"));
        q.setFlagged(true);
        q.setFlagReason("共情不足");
        q.setEvaluatedAt(Instant.now());
        return q;
    }

    // ===== getFlaggedSessions =====

    @Test
    @DisplayName("getFlaggedSessions 返回低分会话列表（rating<=2）")
    void flaggedSessions() {
        CounselingSession s = session(1, studentUserId);
        when(sessionMapper.selectList(any())).thenReturn(List.of(s));

        ApiResponse<List<Map<String, Object>>> resp = controller.getFlaggedSessions(teacherAuth());

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data()).hasSize(1);
        assertThat(resp.data().get(0).get("sessionId")).isEqualTo(s.getSessionId());
        assertThat(resp.data().get(0).get("rating")).isEqualTo(1);
        assertThat(resp.data().get(0).get("comment")).isEqualTo("需要关注");
        verify(sessionMapper).selectList(any());
    }

    // ===== getQualityStats =====

    @Test
    @DisplayName("getQualityStats 计算低分计数与 flagRate（1 星 2 星计入）")
    void qualityStats() {
        when(teacherService.getSatisfactionStats(tenantId))
                .thenReturn(new TeacherService.SatisfactionStatsVO(10, 3.5,
                        List.of(
                                new TeacherService.RatingDistItem(1, 1L),
                                new TeacherService.RatingDistItem(2, 2L),
                                new TeacherService.RatingDistItem(3, 3L),
                                new TeacherService.RatingDistItem(4, 2L),
                                new TeacherService.RatingDistItem(5, 2L)),
                        5, 4.0));

        ApiResponse<Map<String, Object>> resp = controller.getQualityStats(teacherAuth());

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data().get("totalRated")).isEqualTo(10L);
        assertThat(resp.data().get("flaggedCount")).isEqualTo(3L);
        assertThat(resp.data().get("flagRate")).isEqualTo(30.0);
        assertThat(resp.data().get("recentAvg")).isEqualTo(4.0);
    }

    @Test
    @DisplayName("getQualityStats totalRated=0 → flagRate=0 不除零")
    void qualityStats_zeroRated() {
        when(teacherService.getSatisfactionStats(tenantId))
                .thenReturn(new TeacherService.SatisfactionStatsVO(0, 0.0,
                        List.of(new TeacherService.RatingDistItem(1, 0L),
                                new TeacherService.RatingDistItem(2, 0L),
                                new TeacherService.RatingDistItem(3, 0L),
                                new TeacherService.RatingDistItem(4, 0L),
                                new TeacherService.RatingDistItem(5, 0L)),
                        0, 0.0));

        ApiResponse<Map<String, Object>> resp = controller.getQualityStats(teacherAuth());

        assertThat(resp.data().get("flagRate")).isEqualTo(0.0);
    }

    // ===== getQualityScores =====

    @Test
    @DisplayName("getQualityScores 基本分页查询 + 学生名 enrich")
    void qualityScores_basic() {
        QualityScore q = score();
        CounselingSession s = session(5, studentUserId);
        User student = new User();
        student.setPseudonym("小星");
        when(qualityScoreMapper.selectCount(any())).thenReturn(1L);
        when(qualityScoreMapper.selectList(any())).thenReturn(List.of(q));
        when(sessionMapper.selectById(q.getSessionId())).thenReturn(s);
        when(userMapper.selectById(studentUserId)).thenReturn(student);

        ApiResponse<Map<String, Object>> resp = controller.getQualityScores(teacherAuth(), null, null, 1, 20);

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data().get("total")).isEqualTo(1L);
        List<?> items = (List<?>) resp.data().get("items");
        assertThat(items).hasSize(1);
        Map<?, ?> row = (Map<?, ?>) items.get(0);
        assertThat(row.get("studentName")).isEqualTo("小星");
        assertThat(row.get("empathyScore")).isEqualTo(new BigDecimal("80"));
        assertThat(row.get("overallScore")).isEqualTo(new BigDecimal("75"));
        assertThat(row.get("flagged")).isEqualTo(true);
        assertThat(row.get("flagReason")).isEqualTo("共情不足");
    }

    @Test
    @DisplayName("getQualityScores flaggedOnly=true → 仅低分标记")
    void qualityScores_flaggedOnly() {
        when(qualityScoreMapper.selectCount(any())).thenReturn(0L);
        when(qualityScoreMapper.selectList(any())).thenReturn(List.of());

        controller.getQualityScores(teacherAuth(), true, null, 1, 20);

        verify(qualityScoreMapper).selectList(any());
    }

    @Test
    @DisplayName("getQualityScores 按学生筛选且无会话 → 空结果直接返回")
    void qualityScores_studentNoSessions() {
        when(sessionMapper.selectList(any())).thenReturn(List.of());

        ApiResponse<Map<String, Object>> resp = controller.getQualityScores(
                teacherAuth(), null, studentUserId, 1, 20);

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data().get("total")).isEqualTo(0);
        assertThat((List<?>) resp.data().get("items")).isEmpty();
        verify(qualityScoreMapper, org.mockito.Mockito.never()).selectCount(any());
    }

    @Test
    @DisplayName("getQualityScores 按学生筛选有会话 → 按 sessionIds 过滤")
    void qualityScores_studentWithSessions() {
        CounselingSession s = session(5, studentUserId);
        when(sessionMapper.selectList(any())).thenReturn(List.of(s));
        when(qualityScoreMapper.selectCount(any())).thenReturn(1L);
        when(qualityScoreMapper.selectList(any())).thenReturn(List.of(score()));
        when(sessionMapper.selectById(any(UUID.class))).thenReturn(s);
        when(userMapper.selectById(studentUserId)).thenReturn(null); // 学生查无 → 未知

        ApiResponse<Map<String, Object>> resp = controller.getQualityScores(
                teacherAuth(), null, studentUserId, 1, 20);

        assertThat(resp.data().get("total")).isEqualTo(1L);
        Map<?, ?> row = (Map<?, ?>) ((List<?>) resp.data().get("items")).get(0);
        assertThat(row.get("studentName")).isEqualTo("未知");
    }

    @Test
    @DisplayName("getQualityScores 会话查无（已删）→ 学生名未知 + 空值维度兜底 0")
    void qualityScores_missingSession() {
        QualityScore q = score();
        q.setEmpathyScore(null);
        q.setFlagReason(null);
        when(qualityScoreMapper.selectCount(any())).thenReturn(1L);
        when(qualityScoreMapper.selectList(any())).thenReturn(List.of(q));
        when(sessionMapper.selectById(q.getSessionId())).thenReturn(null);

        ApiResponse<Map<String, Object>> resp = controller.getQualityScores(teacherAuth(), null, null, 1, 20);

        Map<?, ?> row = (Map<?, ?>) ((List<?>) resp.data().get("items")).get(0);
        assertThat(row.get("studentName")).isEqualTo("未知");
        assertThat(row.get("empathyScore")).isEqualTo(0);
        assertThat(row.get("flagReason")).isEqualTo("");
    }

    // ===== getAiQualityStats =====

    @Test
    @DisplayName("getAiQualityStats 无评分 → 全 0")
    void aiStats_empty() {
        when(qualityScoreMapper.selectList(any())).thenReturn(List.of());

        ApiResponse<Map<String, Object>> resp = controller.getAiQualityStats(teacherAuth());

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data().get("totalEvaluated")).isEqualTo(0);
        assertThat(resp.data().get("avgOverall")).isEqualTo(0);
        assertThat(resp.data().get("flagRate")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("getAiQualityStats 计算均值与低分率（含 null 维度跳过）")
    void aiStats_avg() {
        QualityScore q1 = score();
        QualityScore q2 = score();
        q2.setOverallScore(new BigDecimal("100"));
        q2.setEmpathyScore(null);
        q2.setFlagged(false);
        when(qualityScoreMapper.selectList(any())).thenReturn(List.of(q1, q2));

        ApiResponse<Map<String, Object>> resp = controller.getAiQualityStats(teacherAuth());

        assertThat(resp.data().get("totalEvaluated")).isEqualTo(2);
        assertThat(resp.data().get("avgOverall")).isEqualTo(87.5); // (75+100)/2
        assertThat(resp.data().get("avgEmpathy")).isEqualTo(80.0); // 仅 q1
        assertThat(resp.data().get("flaggedCount")).isEqualTo(1L);
        assertThat(resp.data().get("flagRate")).isEqualTo(50.0);
    }

    // ===== replaySession =====

    @Test
    @DisplayName("replaySession 会话不存在或跨租户 → error 提示")
    void replay_notFound() {
        when(sessionMapper.selectById(any(UUID.class))).thenReturn(null);

        ApiResponse<Map<String, Object>> resp = controller.replaySession(UUID.randomUUID(), teacherAuth());

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data().get("error")).isEqualTo("会话不存在");
    }

    @Test
    @DisplayName("replaySession 成功：审计 + 消息解密 + 评分叠加 + 学生名")
    void replay_success() {
        CounselingSession s = session(5, studentUserId);
        when(sessionMapper.selectById(s.getSessionId())).thenReturn(s);
        User student = new User();
        student.setPseudonym("小星");
        when(userMapper.selectById(studentUserId)).thenReturn(student);

        MessageSummary msg = new MessageSummary();
        msg.setTurnCount(1);
        msg.setSenderType("student");
        msg.setContentSummary("encrypted-content");
        msg.setEmotionLabel("sad");
        msg.setRiskLevel(2);
        when(messageSummaryMapper.selectList(any())).thenReturn(List.of(msg));
        when(fieldEncryptionService.decrypt("encrypted-content")).thenReturn("解密后的内容");

        QualityScore q = score();
        when(qualityScoreMapper.selectOne(any())).thenReturn(q);

        ApiResponse<Map<String, Object>> resp = controller.replaySession(s.getSessionId(), teacherAuth());

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data().get("studentName")).isEqualTo("小星");
        assertThat(resp.data().get("turnCount")).isEqualTo(12);
        List<?> messages = (List<?>) resp.data().get("messages");
        assertThat(messages).hasSize(1);
        assertThat(((Map<?, ?>) messages.get(0)).get("content")).isEqualTo("解密后的内容");
        Map<?, ?> scoreInfo = (Map<?, ?>) resp.data().get("qualityScore");
        assertThat(scoreInfo.get("overallScore")).isEqualTo(new BigDecimal("75"));
        verify(auditLogService).log(tenantId, teacherUserId, "QUALITY_REPLAY",
                "counseling_session", s.getSessionId(), null);
    }

    @Test
    @DisplayName("replaySession 无评分 / 无学生 → 兜底空 Map 与未知")
    void replay_fallback() {
        CounselingSession s = session(5, studentUserId);
        when(sessionMapper.selectById(s.getSessionId())).thenReturn(s);
        when(userMapper.selectById(studentUserId)).thenReturn(null);
        when(messageSummaryMapper.selectList(any())).thenReturn(List.of());
        when(qualityScoreMapper.selectOne(any())).thenReturn(null);

        ApiResponse<Map<String, Object>> resp = controller.replaySession(s.getSessionId(), teacherAuth());

        assertThat(resp.data().get("studentName")).isEqualTo("未知");
        assertThat(resp.data().get("qualityScore")).isEqualTo(Map.of());
        assertThat(((List<?>) resp.data().get("messages"))).isEmpty();
    }
}
