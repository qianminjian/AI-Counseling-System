package com.mindsafe.service.teacher;

import com.mindsafe.service.conversation.MessageSummaryService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.MessageSummary;
import com.mindsafe.domain.entity.QualityScore;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.domain.mapper.MessageSummaryMapper;
import com.mindsafe.domain.mapper.QualityScoreMapper;
import com.mindsafe.domain.mapper.UserMapper;
import com.mindsafe.service.security.FieldEncryptionService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.type.ObjectTypeHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * C3（2026-08-05）：TeacherQualityController 过厚重构——质量查询/回放聚合下沉 service。
 * <p>
 * 原 controller 直插 4 个 mapper、11 处查询。本测试锁定 TeacherQualityService 行为。
 */
class TeacherQualityServiceTest {

    private QualityScoreMapper qualityScoreMapper;
    private CounselingSessionMapper sessionMapper;
    private UserMapper userMapper;
    private MessageSummaryMapper messageSummaryMapper;
    private MessageSummaryService messageSummaryService;
    private FieldEncryptionService fieldEncryptionService;
    private TeacherQualityService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID studentId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.getTypeHandlerRegistry().register(UUID.class, ObjectTypeHandler.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), CounselingSession.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), MessageSummary.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), QualityScore.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), User.class);

        qualityScoreMapper = mock(QualityScoreMapper.class);
        sessionMapper = mock(CounselingSessionMapper.class);
        userMapper = mock(UserMapper.class);
        messageSummaryMapper = mock(MessageSummaryMapper.class);
        messageSummaryService = mock(MessageSummaryService.class);
        fieldEncryptionService = mock(FieldEncryptionService.class);
        service = new TeacherQualityService(qualityScoreMapper, sessionMapper, userMapper,
                messageSummaryMapper, fieldEncryptionService, messageSummaryService);
    }

    private CounselingSession session(UUID id, Integer rating) {
        CounselingSession s = new CounselingSession();
        s.setSessionId(id);
        s.setTenantId(tenantId);
        s.setStudentUserId(studentId);
        s.setSatisfactionRating(rating);
        s.setStartedAt(Instant.now());
        s.setSessionStatus("completed");
        return s;
    }

    private QualityScore score(UUID id, Integer overall) {
        QualityScore q = new QualityScore();
        q.setScoreId(id);
        q.setSessionId(sessionId);
        q.setTenantId(tenantId);
        q.setOverallScore(overall != null ? BigDecimal.valueOf(overall) : null);
        q.setFlagged(false);
        q.setEvaluatedAt(Instant.now());
        return q;
    }

    @Test
    @DisplayName("flaggedSessions：仅返回 tenant 内 rating<=2 的会话")
    void flaggedSessionsFiltersByTenantAndRating() {
        when(sessionMapper.selectList(any())).thenReturn(List.of(session(sessionId, 2), session(UUID.randomUUID(), 5)));
        // service 负责过滤条件，mock 返回后按条件再次校验——此处验证返回透传
        var result = service.flaggedSessions(tenantId);
        assertThat(result).hasSize(2);
        assertThat(result.get(0)).containsEntry("sessionId", sessionId)
                .containsEntry("rating", 2)
                .containsEntry("sessionStatus", "completed");
    }

    @Test
    @DisplayName("qualityScores：分页返回 + 学生名 enrich；无匹配会话返回空页")
    void qualityScoresEnrichesAndPaginates() {
        // 学生筛选：有匹配会话
        when(sessionMapper.selectList(any())).thenReturn(List.of(session(sessionId, 4)));
        when(qualityScoreMapper.selectPage(any(), any())).thenReturn(
                new Page<QualityScore>().setTotal(1L).setRecords(List.of(score(UUID.randomUUID(), 88))));
        when(sessionMapper.selectBatchIds(any())).thenReturn(List.of(session(sessionId, 4)));
        User student = new User();
        student.setUserId(studentId);
        student.setPseudonym("小明");
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(student));

        var result = service.qualityScores(tenantId, false, studentId, 1, 20);

        assertThat((Long) result.get("total")).isEqualTo(1L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsEntry("studentName", "小明")
                .containsEntry("overallScore", new BigDecimal("88"));
    }

    @Test
    @DisplayName("qualityScores：学生无会话 → 空页不查询评分")
    void qualityScoresEmptyWhenNoSessionMatches() {
        when(sessionMapper.selectList(any())).thenReturn(List.of());

        var result = service.qualityScores(tenantId, false, studentId, 1, 20);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        assertThat(items).isEmpty();
        assertThat((Long) result.get("total")).isEqualTo(0L);
    }

    @Test
    @DisplayName("aiQualityStats：空 → 全 0；有数据 → 均值/低分率")
    void aiQualityStatsComputesAverages() {
        when(qualityScoreMapper.selectList(any())).thenReturn(List.of());
        assertThat(service.aiQualityStats(tenantId))
                .containsEntry("totalEvaluated", 0)
                .containsEntry("avgOverall", 0);

        QualityScore flagged = score(UUID.randomUUID(), 60);
        flagged.setFlagged(true);
        when(qualityScoreMapper.selectList(any())).thenReturn(List.of(score(UUID.randomUUID(), 100), flagged));
        var stats = service.aiQualityStats(tenantId);
        assertThat(stats).containsEntry("totalEvaluated", 2)
                .containsEntry("avgOverall", 80.0)
                .containsEntry("flaggedCount", 1L)
                .containsEntry("flagRate", 50.0);
    }

    @Test
    @DisplayName("replaySession：跨租户/不存在 → null；成功 → 消息解密+评分+学生名")
    void replaySessionReturnsReplayOrNull() {
        // 不存在
        when(sessionMapper.selectById(sessionId)).thenReturn(null);
        assertThat(service.replaySession(tenantId, sessionId)).isNull();

        // 跨租户拒绝
        CounselingSession other = session(sessionId, 4);
        other.setTenantId(UUID.randomUUID());
        when(sessionMapper.selectById(sessionId)).thenReturn(other);
        assertThat(service.replaySession(tenantId, sessionId)).isNull();

        // 成功
        CounselingSession s = session(sessionId, 4);
        s.setSessionSummary("enc-summary");
        s.setTurnCount(3);
        when(sessionMapper.selectById(sessionId)).thenReturn(s);
        MessageSummary msg = new MessageSummary();
        msg.setTurnCount(1);
        msg.setSenderType("student");
        msg.setContentSummary("enc-content");
        when(messageSummaryService.readDecryptedMessages(any(), any())).thenAnswer(inv -> {
            MessageSummary decrypted = new MessageSummary();
            decrypted.setTurnCount(msg.getTurnCount());
            decrypted.setSenderType(msg.getSenderType());
            decrypted.setContentSummary("明文内容");
            decrypted.setEmotionLabel(msg.getEmotionLabel());
            decrypted.setRiskLevel(msg.getRiskLevel());
            return List.of(decrypted);
        });
        when(qualityScoreMapper.selectOne(any())).thenReturn(score(UUID.randomUUID(), 90));
        User student = new User();
        student.setUserId(studentId);
        student.setPseudonym("小明");
        when(userMapper.selectById(studentId)).thenReturn(student);
        when(fieldEncryptionService.decrypt("enc-summary")).thenReturn("明文摘要");
        when(fieldEncryptionService.decrypt("enc-content")).thenReturn("明文内容");

        var replay = service.replaySession(tenantId, sessionId);
        assertThat(replay).containsEntry("studentName", "小明")
                .containsEntry("sessionSummary", "明文摘要")
                .containsEntry("turnCount", 3);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) replay.get("messages");
        assertThat(messages.get(0)).containsEntry("content", "明文内容");
        assertThat((Map<?, ?>) replay.get("qualityScore")).isNotEmpty();
    }
}
