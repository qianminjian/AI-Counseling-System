package com.mindsafe.service.conversation;

import com.mindsafe.ai.orchestrator.EmotionOrchestrationEvaluator;
import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.mapper.RiskEventMapper;
import com.mindsafe.service.profile.ProfileEffectivenessTracker;
import com.mindsafe.service.notification.RiskNotifyOutboxService;
import com.mindsafe.service.voice.TrendAnomalySignaler;
import com.mindsafe.service.voice.VoiceEmotionTrendAnalyzer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SessionEndAnalyticsService 单测（RISK-204 / ORCH-008 / VCL-002~003，BL-08 通道）。
 * <p>
 * 覆盖：趋势分析编排、关注信号生成与 BL-08 持久化（source_type=attention / YELLOW）、
 * countWorsening 尾部连续负面计数、失败静默降级。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("会话结束分析聚合服务")
class SessionEndAnalyticsServiceTest {

    @Mock private VoiceEmotionTrendAnalyzer trendAnalyzer;
    @Mock private TrendAnomalySignaler anomalySignaler;
    @Mock private EmotionOrchestrationEvaluator orchestrationEvaluator;
    @Mock private ProfileEffectivenessTracker effectivenessTracker;
    @Mock private RiskEventMapper riskEventMapper;
    @Mock private RiskNotifyOutboxService riskNotifyOutboxService;

    private SessionEndAnalyticsService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID studentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new SessionEndAnalyticsService(
                trendAnalyzer, anomalySignaler, orchestrationEvaluator, effectivenessTracker,
                riskEventMapper, riskNotifyOutboxService);
    }

    private VoiceEmotionTrendAnalyzer.TrendResult trend(double negRatio) {
        return new VoiceEmotionTrendAnalyzer.TrendResult(
                VoiceEmotionTrendAnalyzer.Trend.WORSENING, negRatio, 0.2, 5);
    }

    @Nested
    @DisplayName("analyze 全流程")
    class Analyze {

        @Test
        @DisplayName("趋势正常（不通知教师）→ 无信号、不落库，返回 recovery/depth")
        void noSignal_noPersist() {
            when(trendAnalyzer.analyzeTrend(any())).thenReturn(trend(0.3));
            when(trendAnalyzer.shouldNotifyTeacher(any())).thenReturn(false);
            when(orchestrationEvaluator.measureRecovery(any()))
                    .thenReturn(new EmotionOrchestrationEvaluator.RecoveryResult(3, true, "sad", "neutral"));
            when(orchestrationEvaluator.measureDepth(any())).thenReturn(2);

            SessionEndAnalyticsService.AnalyticsResult r = service.analyze(
                    tenantId, studentId, List.of("sad", "neutral"), List.of("sad", "neutral"),
                    List.of("我有点难过", "现在好多了"), "sad");

            assertThat(r.trendResult().negativeRatio()).isEqualTo(0.3);
            assertThat(r.attentionSignal()).isNull();
            assertThat(r.recoveryResult().recovered()).isTrue();
            assertThat(r.sessionDepth()).isEqualTo(2);
            verify(riskEventMapper, never()).insert(any(RiskEvent.class));
        }

        @Test
        @DisplayName("趋势恶化 + 信号生成 → 持久化 BL-08（source_type=attention、risk_level=1 YELLOW）")
        void signal_persistedToBl08() {
            List<String> recent = List.of("happy", "sad", "angry", "fearful");
            when(trendAnalyzer.analyzeTrend(any())).thenReturn(trend(0.8));
            when(trendAnalyzer.shouldNotifyTeacher(any())).thenReturn(true);
            TrendAnomalySignaler.AttentionSignal signal = new TrendAnomalySignaler.AttentionSignal(
                    studentId.toString(), "CONTINUOUS_WORSENING", "连续恶化", true, true, 3);
            when(anomalySignaler.evaluate(eq(studentId.toString()), eq(3), eq(0.8), anyDouble()))
                    .thenReturn(signal);

            SessionEndAnalyticsService.AnalyticsResult r = service.analyze(
                    tenantId, studentId, recent, List.of(), List.of(), "neutral");

            assertThat(r.attentionSignal()).isEqualTo(signal);
            // countWorsening：尾部连续负面 = 3（sad/angry/fearful）
            verify(anomalySignaler).evaluate(eq(studentId.toString()), eq(3), eq(0.8), anyDouble());
            ArgumentCaptor<RiskEvent> captor = ArgumentCaptor.forClass(RiskEvent.class);
            verify(riskEventMapper).insert(captor.capture());
            RiskEvent event = captor.getValue();
            assertThat(event.getSourceType()).isEqualTo("attention");
            assertThat(event.getRiskLevel()).isEqualTo(1);
            assertThat(event.getRiskType()).isEqualTo("voice_trend:CONTINUOUS_WORSENING");
            assertThat(event.getTenantId()).isEqualTo(tenantId);
            assertThat(event.getStudentUserId()).isEqualTo(studentId);
            assertThat(event.getStatus()).isEqualTo("open");
            // P0-4：无通知义务（BL-08 关注通道）→ 标记完成态，防止补偿任务误重试
            verify(riskNotifyOutboxService).markSent(event);
        }

        @Test
        @DisplayName("shouldNotifyTeacher 为 true 但 signaler 返回 null → 不落库")
        void notifyButNullSignal_noPersist() {
            when(trendAnalyzer.analyzeTrend(any())).thenReturn(trend(0.5));
            when(trendAnalyzer.shouldNotifyTeacher(any())).thenReturn(true);
            when(anomalySignaler.evaluate(anyString(), anyInt(), anyDouble(), anyDouble())).thenReturn(null);

            SessionEndAnalyticsService.AnalyticsResult r = service.analyze(
                    tenantId, studentId, List.of("sad"), List.of(), List.of(), "neutral");

            assertThat(r.attentionSignal()).isNull();
            verify(riskEventMapper, never()).insert(any(RiskEvent.class));
        }

        @Test
        @DisplayName("countWorsening 边界：单元素/全正面 → 0")
        void worseningCount_edges() {
            when(trendAnalyzer.analyzeTrend(any())).thenReturn(trend(0.1));
            when(trendAnalyzer.shouldNotifyTeacher(any())).thenReturn(true);
            when(anomalySignaler.evaluate(anyString(), eq(0), anyDouble(), anyDouble())).thenReturn(null);

            service.analyze(tenantId, studentId, List.of("sad"), List.of(), List.of(), "neutral");
            verify(anomalySignaler).evaluate(anyString(), eq(0), anyDouble(), anyDouble());
        }

        @Test
        @DisplayName("分析组件抛异常 → 静默降级，返回空结果不抛出")
        void exception_degrades() {
            when(trendAnalyzer.analyzeTrend(any())).thenThrow(new RuntimeException("boom"));

            SessionEndAnalyticsService.AnalyticsResult r = service.analyze(
                    tenantId, studentId, List.of(), List.of(), List.of(), "neutral");

            assertThat(r.trendResult()).isNull();
            assertThat(r.attentionSignal()).isNull();
            assertThat(r.recoveryResult()).isNull();
            assertThat(r.sessionDepth()).isZero();
        }

        @Test
        @DisplayName("BL-08 持久化失败 → 吞掉异常，不影响主流程")
        void persistFailure_swallowed() {
            when(trendAnalyzer.analyzeTrend(any())).thenReturn(trend(0.9));
            when(trendAnalyzer.shouldNotifyTeacher(any())).thenReturn(true);
            when(anomalySignaler.evaluate(anyString(), anyInt(), anyDouble(), anyDouble()))
                    .thenReturn(new TrendAnomalySignaler.AttentionSignal(
                            studentId.toString(), "HIGH_NEGATIVE_RATIO", "负面占比高", false, true, 0));
            when(riskEventMapper.insert(any(RiskEvent.class))).thenThrow(new RuntimeException("db down"));

            assertThatCode(() -> service.analyze(
                    tenantId, studentId, List.of("sad"), List.of(), List.of(), "neutral"))
                    .doesNotThrowAnyException();
        }
    }
}
