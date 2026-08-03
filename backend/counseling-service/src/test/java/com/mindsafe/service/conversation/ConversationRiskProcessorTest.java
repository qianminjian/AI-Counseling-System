package com.mindsafe.service.conversation;

import com.mindsafe.ai.risk.RiskDetectorService;
import com.mindsafe.ai.risk.RiskScoreCalculator;
import com.mindsafe.ai.risk.SemanticRiskClassifier;
import com.mindsafe.common.dto.risk.RiskDetectionResult;
import com.mindsafe.common.enums.RiskLevel;
import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.mapper.RiskEventMapper;
import com.mindsafe.service.notification.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ConversationRiskProcessor 单元测试（P0-2 审计修复：上帝类拆分后的风险处理内聚职责）。
 * <p>
 * 覆盖：关键词检测委托、语义分类融合规则（只升不降）、多信号融合规则、
 * 风险事件持久化 + 评分 + 通知、情绪建议文案。
 */
class ConversationRiskProcessorTest {

    private RiskDetectorService riskDetectorService;
    private SemanticRiskClassifier semanticRiskClassifier;
    private RiskScoreCalculator riskScoreCalculator;
    private RiskEventMapper riskEventMapper;
    private NotificationService notificationService;

    private ConversationRiskProcessor processor;

    @BeforeEach
    void setUp() {
        riskDetectorService = mock(RiskDetectorService.class);
        semanticRiskClassifier = mock(SemanticRiskClassifier.class);
        riskScoreCalculator = mock(RiskScoreCalculator.class);
        riskEventMapper = mock(RiskEventMapper.class);
        notificationService = mock(NotificationService.class);

        processor = new ConversationRiskProcessor(
                riskDetectorService, semanticRiskClassifier, riskScoreCalculator,
                riskEventMapper, notificationService);

        // 默认评分结果
        when(riskScoreCalculator.calculate(any()))
                .thenReturn(new RiskScoreCalculator.ScoreResult(50, RiskLevel.YELLOW, List.of("test")));
    }

    @Nested
    @DisplayName("RISK-101 关键词检测委托")
    class KeywordDetection {

        @Test
        @DisplayName("委托 riskDetectorService.detect 并返回结果")
        void delegatesToDetectorService() {
            RiskDetectionResult expected = new RiskDetectionResult(
                    RiskLevel.RED, "self_harm", List.of("不想活"), 90, true, "立即通知");
            when(riskDetectorService.detect("我不想活了")).thenReturn(expected);

            RiskDetectionResult result = processor.detectKeywordRisk("我不想活了");

            assertThat(result).isEqualTo(expected);
            verify(riskDetectorService).detect("我不想活了");
        }
    }

    @Nested
    @DisplayName("RISK-202 语义分类融合（只升不降）")
    class SemanticFusion {

        @Test
        @DisplayName("硬规则已 ORANGE → 不调语义分类（省 LLM）")
        void keywordOrange_skipsClassifier() {
            RiskDetectionResult orangeResult = new RiskDetectionResult(
                    RiskLevel.ORANGE, "bullying", List.of("被打"), 60, false, "建议关注");

            RiskDetectionResult result = processor.applySemanticRisk(orangeResult, "安全文本", 4);

            assertThat(result).isEqualTo(orangeResult);
            verify(semanticRiskClassifier, never()).classify(anyString(), any(), any(), anyInt());
        }

        @Test
        @DisplayName("硬规则已 RED → 不调语义分类")
        void keywordRed_skipsClassifier() {
            RiskDetectionResult redResult = new RiskDetectionResult(
                    RiskLevel.RED, "self_harm", List.of("关键词"), 90, true, "立即通知");

            RiskDetectionResult result = processor.applySemanticRisk(redResult, "安全文本", 4);

            assertThat(result).isEqualTo(redResult);
            verify(semanticRiskClassifier, never()).classify(anyString(), any(), any(), anyInt());
        }

        @Test
        @DisplayName("语义返回 null（降级）→ 保持硬规则结果")
        void semanticNull_keepsKeywordResult() {
            RiskDetectionResult safeResult = RiskDetectionResult.safe();
            when(semanticRiskClassifier.classify(anyString(), any(), any(), anyInt())).thenReturn(null);

            RiskDetectionResult result = processor.applySemanticRisk(safeResult, "普通文本", 4);

            assertThat(result).isEqualTo(safeResult);
        }

        @Test
        @DisplayName("语义级别 <= 硬规则级别 → 不升级")
        void semanticNotHigher_keepsKeywordResult() {
            RiskDetectionResult yellowResult = new RiskDetectionResult(
                    RiskLevel.YELLOW, "stress", List.of("压力"), 30, false, "关注");
            when(semanticRiskClassifier.classify(anyString(), any(), any(), anyInt())).thenReturn(RiskLevel.YELLOW);

            RiskDetectionResult result = processor.applySemanticRisk(yellowResult, "文本", 4);

            assertThat(result).isEqualTo(yellowResult);
        }

        @Test
        @DisplayName("语义 RED > 硬规则 GREEN → 升级为 RED")
        void semanticRed_upgradesFromGreen() {
            RiskDetectionResult safeResult = RiskDetectionResult.safe();
            when(semanticRiskClassifier.classify(anyString(), any(), any(), anyInt())).thenReturn(RiskLevel.RED);

            RiskDetectionResult result = processor.applySemanticRisk(safeResult, "如果我消失就好了", 5);

            assertThat(result.level()).isEqualTo(RiskLevel.RED);
            assertThat(result.category()).isEqualTo("llm_semantic");
            assertThat(result.score()).isEqualTo(85);
        }

        @Test
        @DisplayName("语义 ORANGE > 硬规则 GREEN → 升级为 ORANGE")
        void semanticOrange_upgradesFromGreen() {
            RiskDetectionResult safeResult = RiskDetectionResult.safe();
            when(semanticRiskClassifier.classify(anyString(), any(), any(), anyInt())).thenReturn(RiskLevel.ORANGE);

            RiskDetectionResult result = processor.applySemanticRisk(safeResult, "想消失", 4);

            assertThat(result.level()).isEqualTo(RiskLevel.ORANGE);
            assertThat(result.score()).isEqualTo(60);
        }
    }

    @Nested
    @DisplayName("多信号融合规则")
    class SignalFusion {

        private final RiskDetectionResult safeResult = RiskDetectionResult.safe();
        private final RiskDetectionResult yellowResult = new RiskDetectionResult(
                RiskLevel.YELLOW, "stress", List.of(), 30, false, "");
        private final RiskDetectionResult orangeResult = new RiskDetectionResult(
                RiskLevel.ORANGE, "bullying", List.of(), 60, false, "");
        private final RiskDetectionResult redResult = new RiskDetectionResult(
                RiskLevel.RED, "self_harm", List.of(), 90, true, "");

        @Test
        @DisplayName("规则1：文本 RED → 直接 RED（不可降级）")
        void textRed_alwaysRed() {
            assertThat(processor.fuseRiskSignals(redResult, "happy", 0.9, 0)).isEqualTo(RiskLevel.RED);
            assertThat(processor.fuseRiskSignals(redResult, null, null, 0)).isEqualTo(RiskLevel.RED);
        }

        @Test
        @DisplayName("规则2：文本 ORANGE + 语音消极 → 升级 RED")
        void textOrange_negativeVoice_red() {
            RiskLevel result = processor.fuseRiskSignals(orangeResult, "sad", 0.8, 0);
            assertThat(result).isEqualTo(RiskLevel.RED);
        }

        @Test
        @DisplayName("规则3：文本 ORANGE（无语音加成）→ ORANGE")
        void textOrange_noVoice_orange() {
            assertThat(processor.fuseRiskSignals(orangeResult, null, null, 0)).isEqualTo(RiskLevel.ORANGE);
            assertThat(processor.fuseRiskSignals(orangeResult, "happy", 0.9, 0)).isEqualTo(RiskLevel.ORANGE);
        }

        @Test
        @DisplayName("规则4：文本 YELLOW + 语音消极 → 升级 ORANGE")
        void textYellow_negativeVoice_orange() {
            RiskLevel result = processor.fuseRiskSignals(yellowResult, "fearful", 0.7, 0);
            assertThat(result).isEqualTo(RiskLevel.ORANGE);
        }

        @Test
        @DisplayName("规则4b：文本 YELLOW（无语音加成）→ YELLOW")
        void textYellow_noVoice_yellow() {
            assertThat(processor.fuseRiskSignals(yellowResult, null, null, 0)).isEqualTo(RiskLevel.YELLOW);
        }

        @Test
        @DisplayName("规则5：无文本风险 + 连续3次消极语音 → YELLOW（趋势预警）")
        void noTextRisk_consecutiveNegative_yellow() {
            RiskLevel result = processor.fuseRiskSignals(safeResult, "sad", 0.8, 3);
            assertThat(result).isEqualTo(RiskLevel.YELLOW);
        }

        @Test
        @DisplayName("规则6：无文本风险 + 单次消极语音 → 不触发风险事件")
        void noTextRisk_singleNegative_null() {
            RiskLevel result = processor.fuseRiskSignals(safeResult, "sad", 0.8, 1);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("语音置信度不足（<=0.6）→ 不算消极语音")
        void lowConfidenceVoice_notNegative() {
            RiskLevel result = processor.fuseRiskSignals(orangeResult, "sad", 0.5, 0);
            assertThat(result).isEqualTo(RiskLevel.ORANGE); // 不升级
        }
    }

    @Nested
    @DisplayName("RISK-203 风险事件持久化")
    class RiskPersistence {

        @Test
        @DisplayName("成功持久化 + 评分计算 + 教师通知")
        void persistsEvent_calculatesScore_notifies() {
            SessionState session = new SessionState(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    "sad", "web", "male", null, 4);
            RiskDetectionResult risk = new RiskDetectionResult(
                    RiskLevel.ORANGE, "bullying", List.of("被打"), 60, false, "建议关注");

            processor.persistRiskEvent(session, risk);

            verify(riskEventMapper).insert(any(RiskEvent.class));
            verify(riskScoreCalculator).calculate(any());
            verify(notificationService).notifyRiskEvent(any(RiskEvent.class));
        }

        @Test
        @DisplayName("持久化异常 fail-fast 上抛（安全关键记录不允许静默丢失）")
        void insertFailure_rethrows() {
            SessionState session = new SessionState(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    "sad", "web", "male", null, 4);
            RiskDetectionResult risk = new RiskDetectionResult(
                    RiskLevel.RED, "self_harm", List.of(), 90, true, "");
            when(riskEventMapper.insert(any(RiskEvent.class))).thenThrow(new RuntimeException("DB 连接失败"));

            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> processor.persistRiskEvent(session, risk));
            verify(notificationService, org.mockito.Mockito.never()).notifyRiskEvent(any(RiskEvent.class));
        }

        @Test
        @DisplayName("教师通知失败不影响持久化结果（尽力而为）")
        void notifyFailure_doesNotThrow() {
            SessionState session = new SessionState(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    "sad", "web", "male", null, 4);
            RiskDetectionResult risk = new RiskDetectionResult(
                    RiskLevel.ORANGE, "bullying", List.of("被打"), 60, false, "建议关注");
            org.mockito.Mockito.doThrow(new RuntimeException("企业微信不可用"))
                    .when(notificationService).notifyRiskEvent(any(RiskEvent.class));

            // 不应抛出异常
            processor.persistRiskEvent(session, risk);
            verify(riskEventMapper).insert(any(RiskEvent.class));
        }
    }

    @Nested
    @DisplayName("情绪建议文案")
    class EmotionSuggestion {

        @Test
        @DisplayName("各情绪类型返回对应建议")
        void returnsAppropriateSuggestion() {
            assertThat(processor.buildEmotionSuggestion("sad")).contains("低落");
            assertThat(processor.buildEmotionSuggestion("fearful")).contains("恐惧");
            assertThat(processor.buildEmotionSuggestion("angry")).contains("激动");
            assertThat(processor.buildEmotionSuggestion("unknown")).contains("异常");
        }
    }
}
