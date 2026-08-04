package com.mindsafe.service.prompt;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.QualityScore;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.domain.mapper.QualityScoreMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Prompt eval 分数从库读数（fix-gate：发布门禁拒绝自报分数）
 * <p>
 * 口径与 AdminPromptController#abComparison 一致：
 * counseling_sessions.prompt_version（versionTag 精确匹配）关联 quality_scores，
 * 四维（共情/CBT完成度/安全合规/参与度）各自取均值后再平均。
 */
@Component
public class PromptEvalScoreReader {

    /** 门禁生效所需的最少评分样本数（quality_scores 行数） */
    public static final int MIN_EVAL_SAMPLES = 5;

    /** 单次读数扫描的会话上限（取最近 N 条，避免全表聚合） */
    private static final int MAX_SESSION_SCAN = 500;

    private final CounselingSessionMapper sessionMapper;
    private final QualityScoreMapper qualityScoreMapper;

    public PromptEvalScoreReader(CounselingSessionMapper sessionMapper,
                                 QualityScoreMapper qualityScoreMapper) {
        this.sessionMapper = sessionMapper;
        this.qualityScoreMapper = qualityScoreMapper;
    }

    /** eval 统计结果 */
    public record EvalStat(
            int sessionCount,
            int scoredCount,
            double overallScore
    ) {
    }

    /**
     * 按 versionTag 从库中读取 eval 统计。
     *
     * @param versionTag 版本标识（如 SYS_001:v2:control）
     * @return 会话数 / 评分样本数 / 四维综合均分（无数据时为 0.0）
     */
    public EvalStat read(String versionTag) {
        List<CounselingSession> sessions = sessionMapper.selectList(
                new LambdaQueryWrapper<CounselingSession>()
                        .eq(CounselingSession::getPromptVersion, versionTag)
                        .orderByDesc(CounselingSession::getStartedAt)
                        .last("LIMIT " + MAX_SESSION_SCAN));
        if (sessions.isEmpty()) {
            return new EvalStat(0, 0, 0.0);
        }

        List<UUID> sessionIds = sessions.stream().map(CounselingSession::getSessionId).toList();
        List<QualityScore> scores = qualityScoreMapper.selectList(
                new LambdaQueryWrapper<QualityScore>()
                        .in(QualityScore::getSessionId, sessionIds));
        if (scores.isEmpty()) {
            return new EvalStat(sessions.size(), 0, 0.0);
        }

        double avgEmpathy = dimensionMean(scores, q -> q.getEmpathyScore());
        double avgCbt = dimensionMean(scores, q -> q.getCbtCompletion());
        double avgSafety = dimensionMean(scores, q -> q.getSafetyCompliance());
        double avgEngagement = dimensionMean(scores, q -> q.getEngagementScore());
        double overall = (avgEmpathy + avgCbt + avgSafety + avgEngagement) / 4.0;
        return new EvalStat(sessions.size(), scores.size(), overall);
    }

    private double dimensionMean(List<QualityScore> scores,
                                 java.util.function.Function<QualityScore, java.math.BigDecimal> getter) {
        return scores.stream()
                .map(getter)
                .filter(java.util.Objects::nonNull)
                .mapToDouble(java.math.BigDecimal::doubleValue)
                .average()
                .orElse(0.0);
    }
}
