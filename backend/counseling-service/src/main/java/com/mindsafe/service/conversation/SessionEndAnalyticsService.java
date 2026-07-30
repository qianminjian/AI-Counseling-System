package com.mindsafe.service.conversation;

import com.mindsafe.ai.orchestrator.EmotionOrchestrationEvaluator;
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
 */
@Service
public class SessionEndAnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(SessionEndAnalyticsService.class);

    private final VoiceEmotionTrendAnalyzer trendAnalyzer;
    private final TrendAnomalySignaler anomalySignaler;
    private final EmotionOrchestrationEvaluator orchestrationEvaluator;
    private final ProfileEffectivenessTracker effectivenessTracker;

    public SessionEndAnalyticsService(VoiceEmotionTrendAnalyzer trendAnalyzer,
                                      TrendAnomalySignaler anomalySignaler,
                                      EmotionOrchestrationEvaluator orchestrationEvaluator,
                                      ProfileEffectivenessTracker effectivenessTracker) {
        this.trendAnalyzer = trendAnalyzer;
        this.anomalySignaler = anomalySignaler;
        this.orchestrationEvaluator = orchestrationEvaluator;
        this.effectivenessTracker = effectivenessTracker;
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

            // RISK-204 / VCL-003：趋势异常→教师关注信号
            if (trendAnalyzer.shouldNotifyTeacher(trendResult)) {
                int worseningCount = countWorsening(recentEmotions);
                signal = anomalySignaler.evaluate(
                        studentUserId.toString(),
                        worseningCount,
                        trendResult.negativeRatio(),
                        0.75 // 默认平均置信（后续从 SER 元数据读取）
                );
                if (signal != null) {
                    log.info("趋势关注信号: student={}, type={}, desc={}",
                            studentUserId, signal.signalType(), signal.description());
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

    /**
     * 文本×语音融合检测（单会话内，由 chat 流程调用）。
     */
    public VoiceEmotionTrendAnalyzer.FusionResult fuseEmotions(String textEmotion, String voiceEmotion) {
        return trendAnalyzer.fuse(textEmotion, voiceEmotion);
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
        return emotion != null && List.of("sad", "angry", "anxious", "fearful", "disgusted", "crisis")
                .contains(emotion.toLowerCase());
    }
}
