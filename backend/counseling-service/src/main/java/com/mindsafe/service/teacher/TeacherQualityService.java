package com.mindsafe.service.teacher;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 教师端质量监控聚合服务（LLM-as-Judge 评分查询 / 低分标记 / AI 统计 / 会话抽检回放）
 * <p>
 * C3（2026-08-05）：从 TeacherQualityController 下沉的查询与聚合逻辑。
 * Controller 仅保留 HTTP 编排与参数绑定。
 */
@Service
public class TeacherQualityService {

    private final QualityScoreMapper qualityScoreMapper;
    private final CounselingSessionMapper sessionMapper;
    private final UserMapper userMapper;
    private final MessageSummaryMapper messageSummaryMapper;
    private final FieldEncryptionService fieldEncryptionService;

    public TeacherQualityService(QualityScoreMapper qualityScoreMapper,
                                 CounselingSessionMapper sessionMapper,
                                 UserMapper userMapper,
                                 MessageSummaryMapper messageSummaryMapper,
                                 FieldEncryptionService fieldEncryptionService) {
        this.qualityScoreMapper = qualityScoreMapper;
        this.sessionMapper = sessionMapper;
        this.userMapper = userMapper;
        this.messageSummaryMapper = messageSummaryMapper;
        this.fieldEncryptionService = fieldEncryptionService;
    }

    /** 质量监控：低分会话列表（rating <= 2） */
    public List<Map<String, Object>> flaggedSessions(UUID tenantId) {
        var flagged = sessionMapper.selectList(
                new LambdaQueryWrapper<CounselingSession>()
                        .eq(CounselingSession::getTenantId, tenantId)
                        .isNotNull(CounselingSession::getSatisfactionRating)
                        .le(CounselingSession::getSatisfactionRating, 2)
                        .orderByDesc(CounselingSession::getStartedAt)
                        .last("LIMIT 50")
        );
        return flagged.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sessionId", s.getSessionId());
            m.put("studentUserId", s.getStudentUserId());
            m.put("rating", s.getSatisfactionRating());
            m.put("comment", s.getSatisfactionComment());
            m.put("startedAt", s.getStartedAt());
            m.put("sessionStatus", s.getSessionStatus());
            return m;
        }).toList();
    }

    /**
     * 质量评分列表（支持筛选：仅低分标记 / 学生 / 分页）
     */
    public Map<String, Object> qualityScores(UUID tenantId, Boolean flaggedOnly, UUID studentUserId,
                                             int page, int size) {
        var wrapper = new LambdaQueryWrapper<QualityScore>()
                .eq(QualityScore::getTenantId, tenantId);
        if (Boolean.TRUE.equals(flaggedOnly)) {
            wrapper.eq(QualityScore::getFlagged, true);
        }
        if (studentUserId != null) {
            var sessions = sessionMapper.selectList(
                    new LambdaQueryWrapper<CounselingSession>()
                            .eq(CounselingSession::getTenantId, tenantId)
                            .eq(CounselingSession::getStudentUserId, studentUserId)
                            .select(CounselingSession::getSessionId));
            var sessionIds = sessions.stream().map(CounselingSession::getSessionId).toList();
            if (sessionIds.isEmpty()) {
                return Map.of("items", List.of(), "total", 0L, "page", page, "size", size);
            }
            wrapper.in(QualityScore::getSessionId, sessionIds);
        }
        wrapper.orderByDesc(QualityScore::getEvaluatedAt);

        // AUD-043：分页插件安全化，selectPage 取代 .last("LIMIT ... OFFSET ...") 拼接（total 由插件统计）
        Page<QualityScore> pageResult = qualityScoreMapper.selectPage(new Page<>(page, size), wrapper);
        long total = pageResult.getTotal();
        List<QualityScore> items = pageResult.getRecords();

        List<Map<String, Object>> enriched = new java.util.ArrayList<>();
        // C1: 批量 enrich——selectBatchIds 批量查会话与学生，替代逐条 selectById（N+1 消除）
        List<UUID> scoreSessionIds = items.stream()
                .map(QualityScore::getSessionId).distinct().toList();
        Map<UUID, CounselingSession> sessionMap = new HashMap<>();
        if (!scoreSessionIds.isEmpty()) {
            List<CounselingSession> sessions = sessionMapper.selectBatchIds(scoreSessionIds);
            if (sessions != null) {
                sessions.forEach(s -> sessionMap.put(s.getSessionId(), s));
            }
        }
        List<UUID> enrichStudentIds = sessionMap.values().stream()
                .map(CounselingSession::getStudentUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<UUID, User> userMap = new HashMap<>();
        if (!enrichStudentIds.isEmpty()) {
            List<User> users = userMapper.selectBatchIds(enrichStudentIds);
            if (users != null) {
                users.forEach(u -> userMap.put(u.getUserId(), u));
            }
        }
        for (QualityScore qs : items) {
            CounselingSession session = sessionMap.get(qs.getSessionId());
            String studentName = null;
            if (session != null) {
                User user = userMap.get(session.getStudentUserId());
                if (user != null) studentName = user.getPseudonym();
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("scoreId", qs.getScoreId());
            row.put("sessionId", qs.getSessionId());
            row.put("studentName", studentName != null ? studentName : "未知");
            row.put("empathyScore", qs.getEmpathyScore() != null ? qs.getEmpathyScore() : 0);
            row.put("cbtCompletion", qs.getCbtCompletion() != null ? qs.getCbtCompletion() : 0);
            row.put("safetyCompliance", qs.getSafetyCompliance() != null ? qs.getSafetyCompliance() : 0);
            row.put("engagementScore", qs.getEngagementScore() != null ? qs.getEngagementScore() : 0);
            row.put("overallScore", qs.getOverallScore() != null ? qs.getOverallScore() : 0);
            row.put("flagged", Boolean.TRUE.equals(qs.getFlagged()));
            row.put("flagReason", qs.getFlagReason() != null ? qs.getFlagReason() : "");
            row.put("evaluatedAt", qs.getEvaluatedAt() != null ? qs.getEvaluatedAt().toString() : "");
            enriched.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", enriched);
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        return result;
    }

    /**
     * AI 质量统计概览（LLM-as-Judge 评分均值 / 低分率）
     */
    public Map<String, Object> aiQualityStats(UUID tenantId) {
        List<QualityScore> all = qualityScoreMapper.selectList(
                new LambdaQueryWrapper<QualityScore>()
                        .eq(QualityScore::getTenantId, tenantId)
        );

        if (all.isEmpty()) {
            return Map.of("totalEvaluated", 0, "avgOverall", 0,
                    "flaggedCount", 0L, "flagRate", 0.0);
        }

        double avgOverall = all.stream()
                .filter(q -> q.getOverallScore() != null)
                .mapToDouble(q -> q.getOverallScore().doubleValue())
                .average().orElse(0);
        double avgEmpathy = all.stream()
                .filter(q -> q.getEmpathyScore() != null)
                .mapToDouble(q -> q.getEmpathyScore().doubleValue())
                .average().orElse(0);
        double avgSafety = all.stream()
                .filter(q -> q.getSafetyCompliance() != null)
                .mapToDouble(q -> q.getSafetyCompliance().doubleValue())
                .average().orElse(0);
        long flaggedCount = all.stream().filter(q -> Boolean.TRUE.equals(q.getFlagged())).count();

        Map<String, Object> statsResult = new LinkedHashMap<>();
        statsResult.put("totalEvaluated", all.size());
        statsResult.put("avgOverall", Math.round(avgOverall * 100.0) / 100.0);
        statsResult.put("avgEmpathy", Math.round(avgEmpathy * 100.0) / 100.0);
        statsResult.put("avgSafety", Math.round(avgSafety * 100.0) / 100.0);
        statsResult.put("flaggedCount", flaggedCount);
        // P1 审计修复：与 getQualityStats 百分比口径一致
        statsResult.put("flagRate", Math.round((double) flaggedCount / all.size() * 1000.0) / 10.0);
        return statsResult;
    }

    /**
     * 会话抽检回放（对话摘要 + 质量评分叠加）；会话不存在或跨租户返回 null
     */
    public Map<String, Object> replaySession(UUID tenantId, UUID sessionId) {
        CounselingSession session = sessionMapper.selectById(sessionId);
        if (session == null || !tenantId.equals(session.getTenantId())) {
            return null;
        }

        List<MessageSummary> messages = messageSummaryMapper.selectList(
                new LambdaQueryWrapper<MessageSummary>()
                        .eq(MessageSummary::getTenantId, tenantId)
                        .eq(MessageSummary::getSessionId, sessionId)
                        .orderByAsc(MessageSummary::getTurnCount)
                        .orderByAsc(MessageSummary::getCreatedAt)
        );

        List<Map<String, Object>> replayMessages = messages.stream().map(m -> Map.<String, Object>of(
                "turn", m.getTurnCount() != null ? m.getTurnCount() : 0,
                "senderType", m.getSenderType() != null ? m.getSenderType() : "unknown",
                "content", m.getContentSummary() != null ? fieldEncryptionService.decrypt(m.getContentSummary()) : "",
                "emotionLabel", m.getEmotionLabel() != null ? m.getEmotionLabel() : "",
                "riskLevel", m.getRiskLevel() != null ? m.getRiskLevel() : 0
        )).toList();

        QualityScore score = qualityScoreMapper.selectOne(
                new LambdaQueryWrapper<QualityScore>()
                        .eq(QualityScore::getTenantId, tenantId)
                        .eq(QualityScore::getSessionId, sessionId)
        );

        Map<String, Object> scoreInfo = null;
        if (score != null) {
            scoreInfo = Map.of(
                    "empathyScore", score.getEmpathyScore() != null ? score.getEmpathyScore() : 0,
                    "cbtCompletion", score.getCbtCompletion() != null ? score.getCbtCompletion() : 0,
                    "safetyCompliance", score.getSafetyCompliance() != null ? score.getSafetyCompliance() : 0,
                    "engagementScore", score.getEngagementScore() != null ? score.getEngagementScore() : 0,
                    "overallScore", score.getOverallScore() != null ? score.getOverallScore() : 0,
                    "flagged", Boolean.TRUE.equals(score.getFlagged()),
                    "flagReason", score.getFlagReason() != null ? score.getFlagReason() : ""
            );
        }

        String studentName = "未知";
        if (session.getStudentUserId() != null) {
            var user = userMapper.selectById(session.getStudentUserId());
            if (user != null && user.getPseudonym() != null) studentName = user.getPseudonym();
        }

        Map<String, Object> replayResult = new LinkedHashMap<>();
        replayResult.put("sessionId", sessionId);
        replayResult.put("studentName", studentName);
        replayResult.put("startedAt", session.getStartedAt() != null ? session.getStartedAt().toString() : "");
        replayResult.put("endedAt", session.getEndedAt() != null ? session.getEndedAt().toString() : "");
        replayResult.put("turnCount", session.getTurnCount() != null ? session.getTurnCount() : 0);
        // AUDIT-P1-8：session_summary 密文存储，回放时解密（明文兼容透传）
        String sessionSummary = fieldEncryptionService.decrypt(session.getSessionSummary());
        replayResult.put("sessionSummary", sessionSummary != null ? sessionSummary : "");
        replayResult.put("messages", replayMessages);
        replayResult.put("qualityScore", scoreInfo != null ? scoreInfo : Map.of());
        return replayResult;
    }
}
