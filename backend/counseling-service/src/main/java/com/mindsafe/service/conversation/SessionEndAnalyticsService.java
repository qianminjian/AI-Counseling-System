package com.mindsafe.service.conversation;

import com.mindsafe.ai.orchestrator.EmotionOrchestrationEvaluator;
import com.mindsafe.ai.risk.EmotionVocabulary;
import com.mindsafe.common.enums.RiskLevel;
import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.mapper.RiskEventMapper;
import com.mindsafe.service.notification.RiskNotifyOutboxService;
import com.mindsafe.service.risk.RiskEventWriter;
import com.mindsafe.service.profile.ProfileEffectivenessTracker;
import com.mindsafe.service.voice.TrendAnomalySignaler;
import com.mindsafe.service.voice.VoiceEmotionTrendAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 会话结束分析聚合服务（RISK-204 / ORCH-008 / PROF-024 / VCL-002~003）
 * <p>
 * 聚合 4 个纯函数分析组件，由 endSession 异步调用，避免 ConversationServiceImpl 构造器膨胀。
 * <ul>
 *   <li>VoiceEmotionTrendAnalyzer：跨会话语音情绪趋势</li>
 *   <li>TrendAnomalySignaler：趋势异常→教师关注信号（BL-08 通道）</li>
 *   <li>EmotionOrchestrationEvaluator：情绪编排效果量化（稳定回落/深度/适配）</li>
 *   <li>ProfileEffectivenessTracker：画像效果回收（有/无画像质量对比）</li>
 * </ul>
 * <p>
 * RISK-204 接线（BL-08 通道）：趋势关注信号持久化到 risk_events（source_type=attention，
 * risk_level=YELLOW），由教师工作台统一展示降噪（与实时风险事件共用通道，降噪靠 level 区分）。
 */
@Service
public class SessionEndAnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(SessionEndAnalyticsService.class);
    /** BACK-010（doing/95）：默认平均置信（后续从 SER 元数据读取，先收编常量） */
    private static final double DEFAULT_AVG_CONFIDENCE = 0.75;

    private final VoiceEmotionTrendAnalyzer trendAnalyzer;
    private final TrendAnomalySignaler anomalySignaler;
    private final EmotionOrchestrationEvaluator orchestrationEvaluator;
    private final ProfileEffectivenessTracker effectivenessTracker;
    private final RiskEventMapper riskEventMapper;
    private final RiskNotifyOutboxService riskNotifyOutboxService;
    /** S-009（doing/93）：风险事件统一写入入口 */
    private final RiskEventWriter riskEventWriter;

    public SessionEndAnalyticsService(VoiceEmotionTrendAnalyzer trendAnalyzer,
                                      TrendAnomalySignaler anomalySignaler,
                                      EmotionOrchestrationEvaluator orchestrationEvaluator,
                                      ProfileEffectivenessTracker effectivenessTracker,
                                      RiskEventMapper riskEventMapper,
                                      RiskNotifyOutboxService riskNotifyOutboxService,
                                      RiskEventWriter riskEventWriter) {
        this.trendAnalyzer = trendAnalyzer;
        this.anomalySignaler = anomalySignaler;
        this.orchestrationEvaluator = orchestrationEvaluator;
        this.effectivenessTracker = effectivenessTracker;
        this.riskEventMapper = riskEventMapper;
        this.riskNotifyOutboxService = riskNotifyOutboxService;
        this.riskEventWriter = riskEventWriter;
    }

    /** 会话结束分析结果 */
    public record AnalyticsResult(
            VoiceEmotionTrendAnalyzer.TrendResult trendResult,
            TrendAnomalySignaler.AttentionSignal attentionSignal,
            EmotionOrchestrationEvaluator.RecoveryResult recoveryResult,
            int sessionDepth
    ) {
    }

    /**
     * 执行会话结束后的全量分析（异步调用，失败静默降级）。
     *
     * @param tenantId        租户 ID
     * @param studentUserId   学生 ID
     * @param recentEmotions  近 N 次会话 SER 主导情绪（时间正序）
     * @param emotionStates   本会话内情绪状态序列
     * @param studentMessages 本会话学生消息列表
     * @param entryEmotion    入场情绪
     * @return 分析结果（不为 null，各字段可能为 null 表示数据不足）
     */
    public AnalyticsResult analyze(UUID tenantId, UUID studentUserId,
                                   List<String> recentEmotions,
                                   List<String> emotionStates,
                                   List<String> studentMessages,
                                   String entryEmotion) {
        VoiceEmotionTrendAnalyzer.TrendResult trendResult = null;
        TrendAnomalySignaler.AttentionSignal signal = null;
        EmotionOrchestrationEvaluator.RecoveryResult recoveryResult = null;
        int depth = 0;

        try {
            // VCL-002：跨会话语音情绪趋势
            trendResult = trendAnalyzer.analyzeTrend(recentEmotions);

            // RISK-204 / VCL-003：趋势异常→教师关注信号 + 持久化 BL-08 通道
            if (trendAnalyzer.shouldNotifyTeacher(trendResult)) {
                int worseningCount = countWorsening(recentEmotions);
                signal = anomalySignaler.evaluate(
                        studentUserId.toString(),
                        worseningCount,
                        trendResult.negativeRatio(),
                        DEFAULT_AVG_CONFIDENCE // BACK-010：默认平均置信（后续从 SER 元数据读取）
                );
                if (signal != null) {
                    log.info("趋势关注信号: student={}, type={}, desc={}",
                            studentUserId, signal.signalType(), signal.description());
                    // RISK-204：写入 risk_events（BL-08 非实时关注信号通道）
                    persistAttentionSignal(tenantId, studentUserId, signal);
                }
            }

            // ORCH-008：情绪编排效果量化
            recoveryResult = orchestrationEvaluator.measureRecovery(emotionStates);
            depth = orchestrationEvaluator.measureDepth(studentMessages);

            // PROF-024：画像效果回收（当前仅记录日志，待质量评分数据积累后做对比）
            if (depth > 0) {
                log.debug("画像效果回收: student={}, depth={}, recovery={}",
                        studentUserId, depth, recoveryResult.recovered());
            }
        } catch (Exception e) {
            log.debug("会话结束分析降级（不影响业务）: {}", e.getMessage());
        }

        return new AnalyticsResult(trendResult, signal, recoveryResult, depth);
    }

    private int countWorsening(List<String> emotions) {
        if (emotions == null || emotions.size() < 2) return 0;
        // 简化：从尾部向前数连续负面数量
        int count = 0;
        for (int i = emotions.size() - 1; i >= 0; i--) {
            if (isNegative(emotions.get(i))) {
                count++;
            } else {
                break;
            }
        }
        return count;
    }

    private boolean isNegative(String emotion) {
        // ARCH-003：内嵌情绪集合 → EmotionVocabulary 统一判定（anxious/crisis 等全管线一致）
        return EmotionVocabulary.isNegative(emotion);
    }

    /**
     * RISK-204：将趋势关注信号持久化到 risk_events（BL-08 非实时关注信号通道）。
     * <p>
     * source_type=attention 区分于即时风险事件（session/assessment/manual），
     * risk_level=YELLOW(1) 确保不触发即时紧急通知但可在教师工作台关注队列中展示。
     * 失败安全：异常仅记日志，绝不中断主流程。
     */
    private void persistAttentionSignal(UUID tenantId, UUID studentUserId, TrendAnomalySignaler.AttentionSignal signal) {
        try {
            RiskEvent event = new RiskEvent();
            event.setRiskEventId(UUID.randomUUID());
            event.setTenantId(tenantId);
            event.setStudentUserId(studentUserId);
            event.setSourceType("attention");
            event.setRiskType("voice_trend:" + signal.signalType());
            event.setRiskLevel(RiskLevel.YELLOW.severity()); // 非实时关注信号
            event.setDetectedBy("trend_analyzer");
            event.setDetectedAt(java.time.Instant.now());
            event.setStatus(RiskEvent.STATUS_OPEN);
            event.setCreatedAt(java.time.Instant.now());
            event.setUpdatedAt(java.time.Instant.now());
            // S-009（doing/93）：统一写入入口（趋势关注无通知义务 → 标记完成态防误重试）
            riskEventWriter.write(event, false);
            log.info("RISK-204 趋势关注信号已持久化: riskEventId={}, type={}", event.getRiskEventId(), signal.signalType());
        } catch (Exception e) {
            log.warn("RISK-204 持久化降级（不影响业务）: {}", e.getMessage());
        }
    }
}
