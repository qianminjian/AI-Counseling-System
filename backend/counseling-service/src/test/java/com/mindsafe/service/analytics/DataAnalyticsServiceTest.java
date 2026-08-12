package com.mindsafe.service.analytics;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.MessageSummary;
import com.mindsafe.domain.entity.QualityScore;
import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.domain.mapper.MessageSummaryMapper;
import com.mindsafe.domain.mapper.QualityScoreMapper;
import com.mindsafe.domain.mapper.RiskEventMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.type.ObjectTypeHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DataAnalyticsService 单元测试（P1 覆盖率冲刺：干预效果/成长轨迹/校级报告）
 */
class DataAnalyticsServiceTest {

    private CounselingSessionMapper sessionMapper;
    private MessageSummaryMapper messageSummaryMapper;
    private RiskEventMapper riskEventMapper;
    private QualityScoreMapper qualityScoreMapper;
    private DataAnalyticsService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID studentUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.getTypeHandlerRegistry().register(UUID.class, ObjectTypeHandler.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), CounselingSession.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), MessageSummary.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), RiskEvent.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), QualityScore.class);

        sessionMapper = mock(CounselingSessionMapper.class);
        messageSummaryMapper = mock(MessageSummaryMapper.class);
        riskEventMapper = mock(RiskEventMapper.class);
        qualityScoreMapper = mock(QualityScoreMapper.class);
        service = new DataAnalyticsService(sessionMapper, messageSummaryMapper, riskEventMapper, qualityScoreMapper);
    }

    private CounselingSession session(Instant startedAt, Integer rating, Integer turns, String emotionTag) {
        CounselingSession s = new CounselingSession();
        s.setSessionId(UUID.randomUUID());
        s.setTenantId(tenantId);
        s.setStudentUserId(studentUserId);
        s.setStartedAt(startedAt);
        s.setSessionStatus("closed");
        s.setSatisfactionRating(rating);
        s.setTurnCount(turns);
        s.setEmotionTag(emotionTag);
        return s;
    }

    private MessageSummary summary(Instant createdAt, String emotionLabel) {
        MessageSummary m = new MessageSummary();
        m.setSummaryId(UUID.randomUUID());
        m.setTenantId(tenantId);
        m.setStudentUserId(studentUserId);
        m.setCreatedAt(createdAt);
        m.setEmotionLabel(emotionLabel);
        return m;
    }

    private RiskEvent riskEvent(Instant createdAt, int level, String status) {
        RiskEvent r = new RiskEvent();
        r.setRiskEventId(UUID.randomUUID());
        r.setTenantId(tenantId);
        r.setStudentUserId(studentUserId);
        r.setCreatedAt(createdAt);
        r.setUpdatedAt(createdAt);
        // P2-5：口径统一 detectedAt（查询/时间线均以 detectedAt 为准）
        r.setDetectedAt(createdAt);
        r.setRiskLevel(level);
        r.setRiskType("self_harm");
        r.setStatus(status);
        return r;
    }

    // ===== DATA-001 干预效果 =====

    @Test
    @DisplayName("interventionEffect 完整数据 → 各指标对比 + 综合判断")
    void interventionEffect_fullData() {
        Instant now = Instant.now();
        List<CounselingSession> pre = List.of(
                session(now.minusSeconds(200000), 3, 5, "sad"),
                session(now.minusSeconds(100000), 3, 6, "angry"));
        List<CounselingSession> post = List.of(
                session(now.minusSeconds(1000), 5, 9, "calm"));
        when(sessionMapper.selectList(any())).thenReturn(pre, post);
        // 前期负面情绪占比高（2 条都负面），后期 0
        when(messageSummaryMapper.selectList(any())).thenReturn(
                List.of(summary(now.minusSeconds(200000), "sad"), summary(now.minusSeconds(100000), "angry")),
                List.of(summary(now.minusSeconds(1000), "happy")));
        // 风险事件：前期每周 1 条 → 后期 0
        when(riskEventMapper.selectCount(any())).thenReturn(1L, 0L);

        Map<String, Object> result = service.interventionEffect(tenantId, studentUserId,
                LocalDate.of(2026, 7, 1), 30);

        assertThat(result.get("preSessionCount")).isEqualTo(2);
        assertThat(result.get("postSessionCount")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) result.get("metrics");
        assertThat(metrics).containsKeys("negativeEmotionRatio", "riskEventPerWeek", "satisfaction", "avgTurns");
        @SuppressWarnings("unchecked")
        Map<String, Object> negRatio = (Map<String, Object>) metrics.get("negativeEmotionRatio");
        assertThat(negRatio.get("pre")).isEqualTo(1.0);
        assertThat(negRatio.get("post")).isEqualTo(0.0);
        assertThat(negRatio.get("improved")).isEqualTo(true);
        // 满意度上升（lowerIsBetter=false）
        @SuppressWarnings("unchecked")
        Map<String, Object> sat = (Map<String, Object>) metrics.get("satisfaction");
        assertThat(sat.get("improved")).isEqualTo(true);
        // 4 项中 ≥3 改善 → significant_improvement
        assertThat(result.get("verdict")).isEqualTo("significant_improvement");
    }

    @Test
    @DisplayName("interventionEffect 无数据 → 全零指标 + no_significant_change")
    void interventionEffect_empty() {
        when(sessionMapper.selectList(any())).thenReturn(List.of(), List.of());
        when(messageSummaryMapper.selectList(any())).thenReturn(List.of(), List.of());
        when(riskEventMapper.selectCount(any())).thenReturn(0L, 0L);

        Map<String, Object> result = service.interventionEffect(tenantId, studentUserId,
                LocalDate.of(2026, 7, 1), 30);

        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) result.get("metrics");
        @SuppressWarnings("unchecked")
        Map<String, Object> sat = (Map<String, Object>) metrics.get("satisfaction");
        assertThat(sat.get("pre")).isEqualTo(0.0);
        assertThat(sat.get("post")).isEqualTo(0.0);
        assertThat(sat.get("effectSize")).isEqualTo(0.0);
        assertThat(result.get("verdict")).isEqualTo("no_significant_change");
    }

    // ===== DATA-002 成长轨迹 =====

    @Test
    @DisplayName("growthTrajectory 10 会话 + 已解决风险 → 里程碑完整")
    void growthTrajectory_fullData() {
        Instant now = Instant.now();
        List<CounselingSession> sessions = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            sessions.add(session(now.minusSeconds((10 - i) * 3600), 4, 6, i == 1 ? "calm" : "neutral"));
        }
        when(sessionMapper.selectList(any())).thenReturn(sessions);
        List<RiskEvent> riskEvents = List.of(
                riskEvent(now.minusSeconds(20000), 3, "resolved"),
                riskEvent(now.minusSeconds(10000), 2, "open"));
        when(riskEventMapper.selectList(any())).thenReturn(riskEvents);
        when(messageSummaryMapper.selectList(any())).thenReturn(
                List.of(summary(now.minusSeconds(30000), "sad"), summary(now.minusSeconds(20000), "happy")));

        Map<String, Object> result = service.growthTrajectory(tenantId, studentUserId,
                LocalDate.of(2026, 2, 15), LocalDate.of(2026, 7, 10));

        assertThat(result.get("totalSessions")).isEqualTo(10);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> milestones = (List<Map<String, Object>>) result.get("milestones");
        assertThat(milestones).extracting(m -> m.get("type"))
                .contains("first_session", "first_positive", "risk_resolved", "ten_sessions");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> riskTimeline = (List<Map<String, Object>>) result.get("riskTimeline");
        assertThat(riskTimeline).hasSize(2);
        assertThat(riskTimeline.get(0).get("status")).isEqualTo("resolved");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> curve = (List<Map<String, Object>>) result.get("emotionCurve");
        assertThat(curve).hasSize(1);
        assertThat(curve.get(0).get("dominantEmotion")).isEqualTo("happy");
        assertThat(curve.get(0).get("negativeRatio")).isEqualTo(0.5);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> freq = (List<Map<String, Object>>) result.get("sessionFrequency");
        // 10 个会话集中在一周内 → 单周分组，会话数不丢失（BA-08：key 统一为 sessions）
        assertThat(freq).hasSize(1);
        assertThat(freq.get(0).get("sessions")).isEqualTo(10L);
    }

    @Test
    @DisplayName("growthTrajectory 空数据 → 空曲线/空里程碑")
    void growthTrajectory_empty() {
        when(sessionMapper.selectList(any())).thenReturn(List.of());
        when(riskEventMapper.selectList(any())).thenReturn(List.of());
        when(messageSummaryMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> result = service.growthTrajectory(tenantId, studentUserId,
                LocalDate.of(2026, 2, 15), LocalDate.of(2026, 7, 10));

        assertThat(result.get("totalSessions")).isEqualTo(0);
        assertThat((List<?>) result.get("milestones")).isEmpty();
        assertThat((List<?>) result.get("emotionCurve")).isEmpty();
        assertThat((List<?>) result.get("riskTimeline")).isEmpty();
        assertThat((List<?>) result.get("sessionFrequency")).isEmpty();
    }

    // ===== DATA-003 校级报告 =====

    @Test
    @DisplayName("schoolReport 完整数据 → 概览/风险分布/处置率/满意度/AI质量/周趋势")
    void schoolReport_fullData() {
        Instant now = Instant.now();
        when(sessionMapper.selectList(any())).thenReturn(List.of(
                session(now.minusSeconds(50000), 5, 8, "calm"),
                session(now.minusSeconds(20000), 3, 6, "neutral")));
        List<RiskEvent> riskEvents = List.of(
                riskEvent(now.minusSeconds(40000), 3, "resolved"),
                riskEvent(now.minusSeconds(30000), 1, "resolved"),
                riskEvent(now.minusSeconds(10000), 2, "open"));
        when(riskEventMapper.selectList(any())).thenReturn(riskEvents);
        QualityScore q1 = new QualityScore();
        q1.setScoreId(UUID.randomUUID());
        q1.setTenantId(tenantId);
        q1.setOverallScore(new BigDecimal("85.5"));
        q1.setFlagged(false);
        q1.setEvaluatedAt(now);
        QualityScore q2 = new QualityScore();
        q2.setScoreId(UUID.randomUUID());
        q2.setTenantId(tenantId);
        q2.setOverallScore(new BigDecimal("70.0"));
        q2.setFlagged(true);
        q2.setEvaluatedAt(now);
        when(qualityScoreMapper.selectList(any())).thenReturn(List.of(q1, q2));

        Map<String, Object> report = service.schoolReport(tenantId,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

        @SuppressWarnings("unchecked")
        Map<String, Object> overview = (Map<String, Object>) report.get("overview");
        assertThat(overview.get("totalSessions")).isEqualTo(2);
        assertThat(overview.get("activeStudents")).isEqualTo(1);
        assertThat(overview.get("totalRiskEvents")).isEqualTo(3);
        assertThat(report.get("riskResolutionRate")).isEqualTo(66.7);
        assertThat(report.get("avgSatisfaction")).isEqualTo(4.0);
        assertThat(report.get("ratedSessionCount")).isEqualTo(2);
        @SuppressWarnings("unchecked")
        Map<Integer, Long> riskDist = (Map<Integer, Long>) report.get("riskDistribution");
        assertThat(riskDist).containsEntry(3, 1L);
        @SuppressWarnings("unchecked")
        Map<String, Object> quality = (Map<String, Object>) report.get("aiQuality");
        assertThat(quality.get("evaluatedCount")).isEqualTo(2);
        assertThat(quality.get("avgOverall")).isEqualTo(77.75);
        assertThat(quality.get("flaggedCount")).isEqualTo(1L);
        assertThat((List<?>) report.get("weeklyTrend")).hasSize(1);
    }

    @Test
    @DisplayName("schoolReport 空数据 → 处置率 100 + 无 aiQuality + 人均会话 0")
    void schoolReport_empty() {
        when(sessionMapper.selectList(any())).thenReturn(List.of());
        when(riskEventMapper.selectList(any())).thenReturn(List.of());
        when(qualityScoreMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> report = service.schoolReport(tenantId,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

        @SuppressWarnings("unchecked")
        Map<String, Object> overview = (Map<String, Object>) report.get("overview");
        // 三目表达式 int 0 被提升为 double 0.0
        assertThat(overview.get("avgSessionsPerStudent")).isEqualTo(0.0);
        assertThat(report.get("riskResolutionRate")).isEqualTo(100.0);
        assertThat(report.get("avgSatisfaction")).isEqualTo(0.0);
        assertThat(report).doesNotContainKey("aiQuality");
        assertThat((List<?>) report.get("weeklyTrend")).isEmpty();
    }

    @Test
    @DisplayName("schoolReport 无质量评分 → 不输出 aiQuality")
    void schoolReport_noScores() {
        Instant now = Instant.now();
        when(sessionMapper.selectList(any())).thenReturn(List.of(session(now, 4, 6, "calm")));
        when(riskEventMapper.selectList(any())).thenReturn(List.of());
        when(qualityScoreMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> report = service.schoolReport(tenantId,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

        assertThat(report).doesNotContainKey("aiQuality");
        @SuppressWarnings("unchecked")
        Map<String, Object> overview = (Map<String, Object>) report.get("overview");
        assertThat(overview.get("avgSessionsPerStudent")).isEqualTo(1.0);
    }
}
