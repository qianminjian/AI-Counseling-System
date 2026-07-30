package com.mindsafe.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.MessageSummary;
import com.mindsafe.domain.entity.QualityScore;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.domain.mapper.MessageSummaryMapper;
import com.mindsafe.domain.mapper.QualityScoreMapper;
import com.mindsafe.domain.mapper.UserMapper;
import com.mindsafe.service.audit.AuditLogService;
import com.mindsafe.service.security.FieldEncryptionService;
import com.mindsafe.service.teacher.TeacherService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 教师端质量监控 API（P1-3 审计修复：从 TeacherController 拆分）
 * <p>
 * 职责：LLM-as-Judge 质量评分查询、低分标记、AI 统计概览、会话抽检回放。
 */
@RestController
@RequestMapping("/api/v1")
public class TeacherQualityController {

    private final QualityScoreMapper qualityScoreMapper;
    private final CounselingSessionMapper sessionMapper;
    private final UserMapper userMapper;
    private final MessageSummaryMapper messageSummaryMapper;
    private final AuditLogService auditLogService;
    private final FieldEncryptionService fieldEncryptionService;
    private final TeacherService teacherService;

    public TeacherQualityController(QualityScoreMapper qualityScoreMapper,
                                    CounselingSessionMapper sessionMapper,
                                    UserMapper userMapper,
                                    MessageSummaryMapper messageSummaryMapper,
                                    AuditLogService auditLogService,
                                    FieldEncryptionService fieldEncryptionService,
                                    TeacherService teacherService) {
        this.qualityScoreMapper = qualityScoreMapper;
        this.sessionMapper = sessionMapper;
        this.userMapper = userMapper;
        this.messageSummaryMapper = messageSummaryMapper;
        this.auditLogService = auditLogService;
        this.fieldEncryptionService = fieldEncryptionService;
        this.teacherService = teacherService;
    }

    /** 质量监控：低分会话列表（rating <= 2） */
    @GetMapping("/teacher/quality/flagged")
    public ApiResponse<List<Map<String, Object>>> getFlaggedSessions(Authentication auth) {
        TenantContext ctx = (TenantContext) auth.getDetails();
        var flagged = sessionMapper.selectList(
                new LambdaQueryWrapper<CounselingSession>()
                        .eq(CounselingSession::getTenantId, ctx.tenantId())
                        .isNotNull(CounselingSession::getSatisfactionRating)
                        .le(CounselingSession::getSatisfactionRating, 2)
                        .orderByDesc(CounselingSession::getStartedAt)
                        .last("LIMIT 50")
        );
        var result = flagged.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sessionId", s.getSessionId());
            m.put("studentUserId", s.getStudentUserId());
            m.put("rating", s.getSatisfactionRating());
            m.put("comment", s.getSatisfactionComment());
            m.put("startedAt", s.getStartedAt());
            m.put("sessionStatus", s.getSessionStatus());
            return m;
        }).toList();
        return ApiResponse.ok(result);
    }

    /** 质量监控：概览指标 */
    @GetMapping("/teacher/quality/stats")
    public ApiResponse<Map<String, Object>> getQualityStats(Authentication auth) {
        TenantContext ctx = (TenantContext) auth.getDetails();
        var stats = teacherService.getSatisfactionStats(ctx.tenantId());
        long flaggedCount = stats.distribution().stream()
                .filter(d -> d.stars() <= 2).mapToLong(d -> d.count()).sum();
        double flagRate = stats.totalRated() > 0 ? (double) flaggedCount / stats.totalRated() * 100 : 0;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalRated", stats.totalRated());
        result.put("avgRating", stats.avgRating());
        result.put("flaggedCount", flaggedCount);
        result.put("flagRate", Math.round(flagRate * 10) / 10.0);
        result.put("recentAvg", stats.recentAvg());
        return ApiResponse.ok(result);
    }

    /**
     * 质量评分列表（支持筛选：仅低分标记 / 学生 / 分页）
     */
    @GetMapping("/teacher/quality/scores")
    public ApiResponse<Map<String, Object>> getQualityScores(
            Authentication auth,
            @RequestParam(required = false) Boolean flaggedOnly,
            @RequestParam(required = false) UUID studentUserId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        TenantContext ctx = (TenantContext) auth.getDetails();

        var wrapper = new LambdaQueryWrapper<QualityScore>()
                .eq(QualityScore::getTenantId, ctx.tenantId());
        if (Boolean.TRUE.equals(flaggedOnly)) {
            wrapper.eq(QualityScore::getFlagged, true);
        }
        if (studentUserId != null) {
            var sessions = sessionMapper.selectList(
                    new LambdaQueryWrapper<CounselingSession>()
                            .eq(CounselingSession::getTenantId, ctx.tenantId())
                            .eq(CounselingSession::getStudentUserId, studentUserId)
                            .select(CounselingSession::getSessionId));
            var sessionIds = sessions.stream().map(CounselingSession::getSessionId).toList();
            if (sessionIds.isEmpty()) {
                return ApiResponse.ok(Map.of("items", List.of(), "total", 0, "page", page, "size", size));
            }
            wrapper.in(QualityScore::getSessionId, sessionIds);
        }
        wrapper.orderByDesc(QualityScore::getEvaluatedAt);

        long total = qualityScoreMapper.selectCount(wrapper);
        wrapper.last("LIMIT " + size + " OFFSET " + (long) (page - 1) * size);
        List<QualityScore> items = qualityScoreMapper.selectList(wrapper);

        List<Map<String, Object>> enriched = new java.util.ArrayList<>();
        for (QualityScore qs : items) {
            var session2 = sessionMapper.selectById(qs.getSessionId());
            String studentName = null;
            if (session2 != null) {
                var user = userMapper.selectById(session2.getStudentUserId());
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
        return ApiResponse.ok(result);
    }

    /**
     * AI 质量统计概览（LLM-as-Judge 评分均值 / 低分率）
     */
    @GetMapping("/teacher/quality/ai-stats")
    public ApiResponse<Map<String, Object>> getAiQualityStats(Authentication auth) {
        TenantContext ctx = (TenantContext) auth.getDetails();

        List<QualityScore> all = qualityScoreMapper.selectList(
                new LambdaQueryWrapper<QualityScore>()
                        .eq(QualityScore::getTenantId, ctx.tenantId())
        );

        if (all.isEmpty()) {
            return ApiResponse.ok(Map.of("totalEvaluated", 0, "avgOverall", 0,
                    "flaggedCount", 0, "flagRate", 0.0));
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
        statsResult.put("flagRate", Math.round((double) flaggedCount / all.size() * 100.0) / 100.0);
        return ApiResponse.ok(statsResult);
    }

    /**
     * 会话抽检回放（对话摘要 + 质量评分叠加）
     */
    @GetMapping("/teacher/quality/sessions/{sessionId}/replay")
    public ApiResponse<Map<String, Object>> replaySession(@PathVariable UUID sessionId, Authentication auth) {
        TenantContext ctx = (TenantContext) auth.getDetails();
        auditLogService.log(ctx.tenantId(), (UUID) auth.getPrincipal(), "QUALITY_REPLAY", "counseling_session", sessionId, null);

        CounselingSession session = sessionMapper.selectById(sessionId);
        if (session == null || !ctx.tenantId().equals(session.getTenantId())) {
            return ApiResponse.ok(Map.of("error", "会话不存在"));
        }

        List<MessageSummary> messages = messageSummaryMapper.selectList(
                new LambdaQueryWrapper<MessageSummary>()
                        .eq(MessageSummary::getTenantId, ctx.tenantId())
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
                        .eq(QualityScore::getTenantId, ctx.tenantId())
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
        replayResult.put("sessionSummary", session.getSessionSummary() != null ? session.getSessionSummary() : "");
        replayResult.put("messages", replayMessages);
        replayResult.put("qualityScore", scoreInfo != null ? scoreInfo : Map.of());
        return ApiResponse.ok(replayResult);
    }
}
