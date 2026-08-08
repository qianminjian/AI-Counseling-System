package com.mindsafe.service.analytics;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.MessageSummary;
import com.mindsafe.domain.entity.QualityScore;
import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.domain.mapper.MessageSummaryMapper;
import com.mindsafe.domain.mapper.QualityScoreMapper;
import com.mindsafe.domain.mapper.RiskEventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 数据分析服务（DATA-001/002/003）
 * <p>
 * DATA-001: 干预效果量化（前后指标对比 + 简单统计显著性）
 * DATA-002: 学生成长轨迹（学期情绪曲线 + 里程碑 + 风险时间线）
 * DATA-003: 校级报告数据聚合（月度/学期 anonymized 统计）
 */
@Service
public class DataAnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(DataAnalyticsService.class);
    private static final ZoneId ZONE_CN = ZoneId.of("Asia/Shanghai");

    private final CounselingSessionMapper sessionMapper;
    private final MessageSummaryMapper messageSummaryMapper;
    private final RiskEventMapper riskEventMapper;
    private final QualityScoreMapper qualityScoreMapper;

    public DataAnalyticsService(CounselingSessionMapper sessionMapper,
                                MessageSummaryMapper messageSummaryMapper,
                                RiskEventMapper riskEventMapper,
                                QualityScoreMapper qualityScoreMapper) {
        this.sessionMapper = sessionMapper;
        this.messageSummaryMapper = messageSummaryMapper;
        this.riskEventMapper = riskEventMapper;
        this.qualityScoreMapper = qualityScoreMapper;
    }

    // ===== DATA-001: 干预效果量化 =====

    /**
     * 干预效果分析：对比指定时间段前后的核心指标
     * <p>
     * 指标：负面情绪占比、风险事件频率、满意度均值、会话参与度
     * 统计：简单配对比较 + Cohen's d 效应量
     *
     * @param tenantId      租户
     * @param studentUserId 学生
     * @param interventionDate 干预起始日期（前后分界线）
     * @param windowDays    前后窗口天数（默认 30）
     */
    public Map<String, Object> interventionEffect(UUID tenantId, UUID studentUserId,
                                                   LocalDate interventionDate, int windowDays) {
        Instant splitPoint = interventionDate.atStartOfDay(ZONE_CN).toInstant();
        Instant preStart = splitPoint.minus(windowDays, ChronoUnit.DAYS);
        Instant postEnd = splitPoint.plus(windowDays, ChronoUnit.DAYS);

        // 前期会话
        List<CounselingSession> preSessions = getSessions(tenantId, studentUserId, preStart, splitPoint);
        // 后期会话
        List<CounselingSession> postSessions = getSessions(tenantId, studentUserId, splitPoint, postEnd);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("studentUserId", studentUserId);
        result.put("interventionDate", interventionDate.toString());
        result.put("windowDays", windowDays);
        result.put("preSessionCount", preSessions.size());
        result.put("postSessionCount", postSessions.size());

        // 指标对比
        Map<String, Object> metrics = new LinkedHashMap<>();

        // 1. 负面情绪占比
        double preNegRatio = negativeEmotionRatio(tenantId, studentUserId, preStart, splitPoint);
        double postNegRatio = negativeEmotionRatio(tenantId, studentUserId, splitPoint, postEnd);
        metrics.put("negativeEmotionRatio", comparison(preNegRatio, postNegRatio, true));

        // 2. 风险事件频率（每周）
        double preRiskFreq = riskEventFrequency(tenantId, studentUserId, preStart, splitPoint, windowDays);
        double postRiskFreq = riskEventFrequency(tenantId, studentUserId, splitPoint, postEnd, windowDays);
        metrics.put("riskEventPerWeek", comparison(preRiskFreq, postRiskFreq, true));

        // 3. 满意度均值
        double preSat = avgSatisfaction(preSessions);
        double postSat = avgSatisfaction(postSessions);
        metrics.put("satisfaction", comparison(preSat, postSat, false));

        // 4. 会话参与度（平均轮次）
        double preTurns = avgTurns(preSessions);
        double postTurns = avgTurns(postSessions);
        metrics.put("avgTurns", comparison(preTurns, postTurns, false));

        result.put("metrics", metrics);

        // 综合判断
        String verdict = computeVerdict(metrics);
        result.put("verdict", verdict);

        return result;
    }

    // ===== DATA-002: 学生成长轨迹 =====

    /**
     * 学生成长轨迹：学期情绪曲线 + 里程碑 + 风险时间线
     */
    public Map<String, Object> growthTrajectory(UUID tenantId, UUID studentUserId,
                                                 LocalDate semesterStart, LocalDate semesterEnd) {
        Instant start = semesterStart.atStartOfDay(ZONE_CN).toInstant();
        Instant end = semesterEnd.plusDays(1).atStartOfDay(ZONE_CN).toInstant();

        List<CounselingSession> sessions = getSessions(tenantId, studentUserId, start, end);
        List<RiskEvent> riskEvents = getRiskEvents(tenantId, studentUserId, start, end);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("studentUserId", studentUserId);
        result.put("semesterStart", semesterStart.toString());
        result.put("semesterEnd", semesterEnd.toString());
        result.put("totalSessions", sessions.size());

        // 1. 周维度情绪曲线
        result.put("emotionCurve", buildEmotionCurve(tenantId, studentUserId, start, end));

        // 2. 里程碑
        result.put("milestones", buildMilestones(sessions, riskEvents));

        // 3. 风险事件时间线
        List<Map<String, Object>> riskTimeline = riskEvents.stream().map(re -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", re.getCreatedAt().atZone(ZONE_CN).toLocalDate().toString());
            m.put("level", re.getRiskLevel());
            m.put("category", re.getRiskType());
            m.put("status", re.getStatus());
            return m;
        }).collect(Collectors.toList());
        result.put("riskTimeline", riskTimeline);

        // 4. 会话频率趋势（按周，BA-08：与 buildWeeklySessionTrend 去重合并）
        result.put("sessionFrequency", buildWeeklySessionTrend(sessions));

        return result;
    }

    // ===== DATA-003: 校级报告数据聚合 =====

    /**
     * 校级报告：月度/学期 anonymized 统计
     */
    public Map<String, Object> schoolReport(UUID tenantId, LocalDate periodStart, LocalDate periodEnd) {
        Instant start = periodStart.atStartOfDay(ZONE_CN).toInstant();
        Instant end = periodEnd.plusDays(1).atStartOfDay(ZONE_CN).toInstant();

        // 全会话
        List<CounselingSession> allSessions = sessionMapper.selectList(
                new LambdaQueryWrapper<CounselingSession>()
                        .eq(CounselingSession::getTenantId, tenantId)
                        .ge(CounselingSession::getStartedAt, start)
                        .lt(CounselingSession::getStartedAt, end));

        // 活跃学生数
        Set<UUID> activeStudents = allSessions.stream()
                .map(CounselingSession::getStudentUserId).collect(Collectors.toSet());

        // 风险事件
        List<RiskEvent> riskEvents = riskEventMapper.selectList(
                new LambdaQueryWrapper<RiskEvent>()
                        .eq(RiskEvent::getTenantId, tenantId)
                        .ge(RiskEvent::getCreatedAt, start)
                        .lt(RiskEvent::getCreatedAt, end));

        // 质量评分
        List<QualityScore> scores = qualityScoreMapper.selectList(
                new LambdaQueryWrapper<QualityScore>()
                        .eq(QualityScore::getTenantId, tenantId)
                        .ge(QualityScore::getEvaluatedAt, start)
                        .lt(QualityScore::getEvaluatedAt, end));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("periodStart", periodStart.toString());
        report.put("periodEnd", periodEnd.toString());
        report.put("generatedAt", Instant.now().toString());

        // 概览
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("totalSessions", allSessions.size());
        overview.put("activeStudents", activeStudents.size());
        overview.put("avgSessionsPerStudent", activeStudents.isEmpty() ? 0 :
                Math.round((double) allSessions.size() / activeStudents.size() * 10) / 10.0);
        overview.put("totalRiskEvents", riskEvents.size());
        overview.put("riskStudents", riskEvents.stream()
                .map(RiskEvent::getStudentUserId).distinct().count());
        report.put("overview", overview);

        // 风险分布
        Map<Integer, Long> riskByLevel = riskEvents.stream()
                .collect(Collectors.groupingBy(RiskEvent::getRiskLevel, Collectors.counting()));
        report.put("riskDistribution", riskByLevel);

        // 风险处置率
        long resolved = riskEvents.stream().filter(r -> RiskEvent.STATUS_RESOLVED.equals(r.getStatus()) || RiskEvent.STATUS_CLOSED.equals(r.getStatus())).count();
        report.put("riskResolutionRate", riskEvents.isEmpty() ? 100.0 :
                Math.round((double) resolved / riskEvents.size() * 1000) / 10.0);

        // 满意度
        List<CounselingSession> rated = allSessions.stream()
                .filter(s -> s.getSatisfactionRating() != null).collect(Collectors.toList());
        double avgSat = rated.stream().mapToInt(CounselingSession::getSatisfactionRating).average().orElse(0);
        report.put("avgSatisfaction", Math.round(avgSat * 100) / 100.0);
        report.put("ratedSessionCount", rated.size());

        // AI 质量评分均值
        if (!scores.isEmpty()) {
            Map<String, Object> quality = new LinkedHashMap<>();
            quality.put("evaluatedCount", scores.size());
            quality.put("avgOverall", scores.stream()
                    .filter(s -> s.getOverallScore() != null)
                    .mapToDouble(s -> s.getOverallScore().doubleValue()).average().orElse(0));
            quality.put("flaggedCount", scores.stream().filter(s -> Boolean.TRUE.equals(s.getFlagged())).count());
            report.put("aiQuality", quality);
        }

        // 月度趋势（按周分组会话数）
        report.put("weeklyTrend", buildWeeklySessionTrend(allSessions));

        return report;
    }

    // ===== 内部方法 =====

    private List<CounselingSession> getSessions(UUID tenantId, UUID studentUserId, Instant from, Instant to) {
        return sessionMapper.selectList(new LambdaQueryWrapper<CounselingSession>()
                .eq(CounselingSession::getTenantId, tenantId)
                .eq(CounselingSession::getStudentUserId, studentUserId)
                .ge(CounselingSession::getStartedAt, from)
                .lt(CounselingSession::getStartedAt, to)
                .orderByAsc(CounselingSession::getStartedAt));
    }

    private List<RiskEvent> getRiskEvents(UUID tenantId, UUID studentUserId, Instant from, Instant to) {
        return riskEventMapper.selectList(new LambdaQueryWrapper<RiskEvent>()
                .eq(RiskEvent::getTenantId, tenantId)
                .eq(RiskEvent::getStudentUserId, studentUserId)
                .ge(RiskEvent::getCreatedAt, from)
                .lt(RiskEvent::getCreatedAt, to)
                .orderByAsc(RiskEvent::getCreatedAt));
    }

    private double negativeEmotionRatio(UUID tenantId, UUID studentUserId, Instant from, Instant to) {
        List<MessageSummary> summaries = messageSummaryMapper.selectList(
                new LambdaQueryWrapper<MessageSummary>()
                        .eq(MessageSummary::getTenantId, tenantId)
                        .eq(MessageSummary::getStudentUserId, studentUserId)
                        .eq(MessageSummary::getSenderType, User.USER_TYPE_STUDENT)
                        .ge(MessageSummary::getCreatedAt, from)
                        .lt(MessageSummary::getCreatedAt, to));
        if (summaries.isEmpty()) return 0;
        Set<String> negativeLabels = Set.of("sad", "angry", "scared", "nervous", "lonely", "tired");
        long negCount = summaries.stream()
                .filter(s -> s.getEmotionLabel() != null && negativeLabels.contains(s.getEmotionLabel()))
                .count();
        return (double) negCount / summaries.size();
    }

    private double riskEventFrequency(UUID tenantId, UUID studentUserId, Instant from, Instant to, int windowDays) {
        long count = riskEventMapper.selectCount(new LambdaQueryWrapper<RiskEvent>()
                .eq(RiskEvent::getTenantId, tenantId)
                .eq(RiskEvent::getStudentUserId, studentUserId)
                .ge(RiskEvent::getCreatedAt, from)
                .lt(RiskEvent::getCreatedAt, to));
        double weeks = windowDays / 7.0;
        return weeks > 0 ? count / weeks : 0;
    }

    private double avgSatisfaction(List<CounselingSession> sessions) {
        return sessions.stream()
                .filter(s -> s.getSatisfactionRating() != null)
                .mapToInt(CounselingSession::getSatisfactionRating)
                .average().orElse(0);
    }

    private double avgTurns(List<CounselingSession> sessions) {
        return sessions.stream()
                .filter(s -> s.getTurnCount() != null)
                .mapToInt(CounselingSession::getTurnCount)
                .average().orElse(0);
    }

    private Map<String, Object> comparison(double pre, double post, boolean lowerIsBetter) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pre", Math.round(pre * 1000) / 1000.0);
        m.put("post", Math.round(post * 1000) / 1000.0);
        double change = post - pre;
        m.put("change", Math.round(change * 1000) / 1000.0);
        // Cohen's d 简化版（用变化量/前期值作为效应量近似）
        double effectSize = pre != 0 ? Math.abs(change) / Math.abs(pre) : 0;
        m.put("effectSize", Math.round(effectSize * 100) / 100.0);
        boolean improved = lowerIsBetter ? change < 0 : change > 0;
        m.put("improved", improved);
        // 效应量解读
        String magnitude = effectSize < 0.2 ? "negligible" : effectSize < 0.5 ? "small" : effectSize < 0.8 ? "medium" : "large";
        m.put("magnitude", magnitude);
        return m;
    }

    private String computeVerdict(Map<String, Object> metrics) {
        long improvedCount = metrics.values().stream()
                .filter(v -> v instanceof Map && Boolean.TRUE.equals(((Map<?, ?>) v).get("improved")))
                .count();
        if (improvedCount >= 3) return "significant_improvement";
        if (improvedCount >= 2) return "moderate_improvement";
        if (improvedCount >= 1) return "slight_improvement";
        return "no_significant_change";
    }

    private List<Map<String, Object>> buildEmotionCurve(UUID tenantId, UUID studentUserId, Instant start, Instant end) {
        List<MessageSummary> summaries = messageSummaryMapper.selectList(
                new LambdaQueryWrapper<MessageSummary>()
                        .eq(MessageSummary::getTenantId, tenantId)
                        .eq(MessageSummary::getStudentUserId, studentUserId)
                        .eq(MessageSummary::getSenderType, User.USER_TYPE_STUDENT)
                        .isNotNull(MessageSummary::getEmotionLabel)
                        .ge(MessageSummary::getCreatedAt, start)
                        .lt(MessageSummary::getCreatedAt, end)
                        .orderByAsc(MessageSummary::getCreatedAt));

        // 按周分组
        Map<String, List<String>> byWeek = new LinkedHashMap<>();
        for (MessageSummary s : summaries) {
            String week = s.getCreatedAt().atZone(ZONE_CN).toLocalDate()
                    .with(java.time.DayOfWeek.MONDAY).toString();
            byWeek.computeIfAbsent(week, k -> new ArrayList<>()).add(s.getEmotionLabel());
        }

        Set<String> negativeLabels = Set.of("sad", "angry", "scared", "nervous", "lonely", "tired");
        List<Map<String, Object>> curve = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : byWeek.entrySet()) {
            List<String> labels = entry.getValue();
            long negCount = labels.stream().filter(negativeLabels::contains).count();
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("weekStart", entry.getKey());
            point.put("messageCount", labels.size());
            point.put("negativeRatio", Math.round((double) negCount / labels.size() * 100) / 100.0);
            point.put("dominantEmotion", labels.stream()
                    .collect(Collectors.groupingBy(l -> l, Collectors.counting()))
                    .entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey).orElse("neutral"));
            curve.add(point);
        }
        return curve;
    }

    private List<Map<String, Object>> buildMilestones(List<CounselingSession> sessions, List<RiskEvent> riskEvents) {
        List<Map<String, Object>> milestones = new ArrayList<>();
        if (!sessions.isEmpty()) {
            milestones.add(milestone("first_session", sessions.get(0).getStartedAt(), "首次使用心理辅导"));
        }
        // 首次正面情绪
        sessions.stream()
                .filter(s -> "happy".equals(s.getEmotionTag()) || "calm".equals(s.getEmotionTag()))
                .findFirst()
                .ifPresent(s -> milestones.add(milestone("first_positive", s.getStartedAt(), "首次正面情绪会话")));
        // 风险事件首次解决
        riskEvents.stream()
                .filter(r -> RiskEvent.STATUS_RESOLVED.equals(r.getStatus()) || RiskEvent.STATUS_CLOSED.equals(r.getStatus()))
                .findFirst()
                .ifPresent(r -> milestones.add(milestone("risk_resolved", r.getUpdatedAt(), "首次风险事件成功处置")));
        // 累计 10 次会话
        if (sessions.size() >= 10) {
            milestones.add(milestone("ten_sessions", sessions.get(9).getStartedAt(), "累计完成 10 次辅导会话"));
        }
        return milestones;
    }

    private Map<String, Object> milestone(String type, Instant date, String label) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("date", date.atZone(ZONE_CN).toLocalDate().toString());
        m.put("label", label);
        return m;
    }

    /** BA-08：按周分组会话数（weekStart → sessions），DATA-002/003 共用（原 buildSessionFrequency 重复实现已删） */
    private List<Map<String, Object>> buildWeeklySessionTrend(List<CounselingSession> sessions) {
        Map<String, Long> byWeek = sessions.stream()
                .collect(Collectors.groupingBy(
                        s -> s.getStartedAt().atZone(ZONE_CN).toLocalDate()
                                .with(java.time.DayOfWeek.MONDAY).toString(),
                        Collectors.counting()));
        return byWeek.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("weekStart", e.getKey());
            m.put("sessions", e.getValue());
            return m;
        }).collect(Collectors.toList());
    }
}
