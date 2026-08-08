package com.mindsafe.service.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindsafe.ai.risk.RiskDetectorService;
import com.mindsafe.ai.risk.RiskScoreCalculator;
import com.mindsafe.ai.risk.SemanticRiskClassifier;
import com.mindsafe.common.dto.risk.RiskDetectionResult;
import com.mindsafe.common.enums.RiskLevel;
import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.mapper.RiskEventMapper;
import com.mindsafe.service.notification.NotificationService;
import com.mindsafe.service.notification.RiskNotifyOutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.mockito.ArgumentCaptor;

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
    private RiskNotifyOutboxService riskNotifyOutboxService;

    private ConversationRiskProcessor processor;

    @BeforeEach
    void setUp() {
        riskDetectorService = mock(RiskDetectorService.class);
        semanticRiskClassifier = mock(SemanticRiskClassifier.class);
        riskScoreCalculator = mock(RiskScoreCalculator.class);
        riskEventMapper = mock(RiskEventMapper.class);
        notificationService = mock(NotificationService.class);
        riskNotifyOutboxService = mock(RiskNotifyOutboxService.class);

        processor = new ConversationRiskProcessor(
                riskDetectorService, semanticRiskClassifier, riskScoreCalculator,
                riskEventMapper, notificationService, riskNotifyOutboxService,
                new ObjectMapper());

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

        @Test
        @DisplayName("语义升级：关键词已有真实类别 → 保留原类别（DC-001：真实类别落库 + 高敏门控可命中）")
        void semanticUpgrade_keepsKeywordCategory() {
            RiskDetectionResult yellowResult = new RiskDetectionResult(
                    RiskLevel.YELLOW, "性侵/性骚扰", List.of("摸隐私部位"), 30, false, "关注");
            when(semanticRiskClassifier.classify(anyString(), any(), any(), anyInt())).thenReturn(RiskLevel.RED);

            RiskDetectionResult result = processor.applySemanticRisk(yellowResult, "文本", 4);

            assertThat(result.level()).isEqualTo(RiskLevel.RED);
            assertThat(result.category()).isEqualTo("性侵/性骚扰");
            assertThat(result.score()).isEqualTo(85);
        }

        @Test
        @DisplayName("语义升级：无关键词类别 → 维持 llm_semantic（原语义兜底）")
        void semanticUpgrade_withoutCategory_keepsLlmsemantic() {
            RiskDetectionResult yellowResult = new RiskDetectionResult(
                    RiskLevel.YELLOW, "未分类", List.of(), 30, false, "关注");
            when(semanticRiskClassifier.classify(anyString(), any(), any(), anyInt())).thenReturn(RiskLevel.RED);

            RiskDetectionResult result = processor.applySemanticRisk(yellowResult, "文本", 4);

            assertThat(result.level()).isEqualTo(RiskLevel.RED);
            assertThat(result.category()).isEqualTo("llm_semantic");
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
        @DisplayName("成功持久化 + 评分计算 + 教师通知 + outbox 标记 sent")
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
            // P0-4：通知成功 → 状态标记 sent，避免补偿任务重复通知
            verify(riskNotifyOutboxService).markSent(any(RiskEvent.class));
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
        @DisplayName("教师通知失败：不抛出但标记 failed 进补偿队列（P0-4）")
        void notifyFailure_doesNotThrow() {
            SessionState session = new SessionState(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    "sad", "web", "male", null, 4);
            RiskDetectionResult risk = new RiskDetectionResult(
                    RiskLevel.ORANGE, "bullying", List.of("被打"), 60, false, "建议关注");
            org.mockito.Mockito.doThrow(new RuntimeException("企业微信不可用"))
                    .when(notificationService).notifyRiskEvent(any(RiskEvent.class));

            // 不应抛出异常（事件已落库），通知失败进补偿队列
            processor.persistRiskEvent(session, risk);
            verify(riskEventMapper).insert(any(RiskEvent.class));
            verify(riskNotifyOutboxService).markFailed(any(RiskEvent.class));
        }

        @Test
        @DisplayName("A2: 结构化评分 score + reason_codes 随事件落库（教师端可解释，不再只打日志）")
        void persistsStructuredScoreAndReasonCodes() throws Exception {
            SessionState session = new SessionState(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    "sad", "web", "male", null, 4);
            RiskDetectionResult risk = new RiskDetectionResult(
                    RiskLevel.ORANGE, "bullying", List.of("被打"), 60, false, "建议关注");
            when(riskScoreCalculator.calculate(any())).thenReturn(new RiskScoreCalculator.ScoreResult(
                    72, RiskLevel.ORANGE, List.of("intent_explicit", "plan_method")));

            processor.persistRiskEvent(session, risk);

            ArgumentCaptor<RiskEvent> captor = ArgumentCaptor.forClass(RiskEvent.class);
            verify(riskEventMapper).insert(captor.capture());
            RiskEvent event = captor.getValue();
            assertThat(event.getRiskScore()).as("结构化评分应随事件落库").isEqualTo(72);
            JsonNode reasons = new ObjectMapper().readTree(event.getReasonCodes());
            assertThat(reasons).as("reason_codes 应为 JSON 数组").hasSize(2);
            assertThat(reasons.get(0).asText()).isEqualTo("intent_explicit");
        }
    }

    @Nested
    @DisplayName("RISK-203 评分因子抽取（审计修复：7/11 维度恒 0）")
    class ScoreFactorExtraction {

        @Test
        @DisplayName("明确自伤意图关键词 → intentWeight=15")
        void explicitIntent_weights15() {
            var f = ConversationRiskProcessor.extractScoreFactors(List.of("想死"));
            assertThat(f.intentWeight()).isEqualTo(15);

            var f2 = ConversationRiskProcessor.extractScoreFactors(List.of("不想活了"));
            assertThat(f2.intentWeight()).isEqualTo(15);
        }

        @Test
        @DisplayName("含混死亡愿望关键词 → intentWeight=8")
        void vagueIntent_weights8() {
            var f = ConversationRiskProcessor.extractScoreFactors(List.of("活着没意思"));
            assertThat(f.intentWeight()).isEqualTo(8);

            var f2 = ConversationRiskProcessor.extractScoreFactors(List.of("把东西送人", "告别"));
            assertThat(f2.intentWeight()).isEqualTo(8);
        }

        @Test
        @DisplayName("非自伤类关键词（霸凌）→ intentWeight=0")
        void nonSelfHarmKeyword_zeroIntent() {
            var f = ConversationRiskProcessor.extractScoreFactors(List.of("被打", "霸凌"));
            assertThat(f.intentWeight()).isZero();
            assertThat(f.cssrsIdeation()).isNull();
        }

        @Test
        @DisplayName("自伤方法关键词每个 +5，上限 20")
        void methods_accumulatePlanWeight() {
            assertThat(ConversationRiskProcessor.extractScoreFactors(List.of("跳楼")).planWeight()).isEqualTo(5);
            assertThat(ConversationRiskProcessor.extractScoreFactors(List.of("跳楼", "上吊")).planWeight()).isEqualTo(10);
            assertThat(ConversationRiskProcessor.extractScoreFactors(List.of("跳楼", "上吊", "割腕", "吃药", "带刀")).planWeight()).isEqualTo(20);
        }

        @Test
        @DisplayName("仅死亡愿望 → cssrsIdeation=DEATH_WISH")
        void deathWish_mapsIdeation() {
            var f = ConversationRiskProcessor.extractScoreFactors(List.of("没希望"));
            assertThat(f.cssrsIdeation()).isEqualTo(RiskScoreCalculator.CssrsIdeation.DEATH_WISH);
        }

        @Test
        @DisplayName("明确意图 → cssrsIdeation=ACTIVE_IDEATION")
        void activeIntent_mapsIdeation() {
            var f = ConversationRiskProcessor.extractScoreFactors(List.of("想死"));
            assertThat(f.cssrsIdeation()).isEqualTo(RiskScoreCalculator.CssrsIdeation.ACTIVE_IDEATION);
        }

        @Test
        @DisplayName("命中方法词 → cssrsIdeation=WITH_METHOD（高于意图档）")
        void methodKeyword_mapsWithMethod() {
            var f = ConversationRiskProcessor.extractScoreFactors(List.of("想死", "割腕"));
            assertThat(f.cssrsIdeation()).isEqualTo(RiskScoreCalculator.CssrsIdeation.WITH_METHOD);
        }

        @Test
        @DisplayName("遗书 → 准备行为 PREPARATORY + WITH_PLAN_INTENT")
        void willNote_mapsPreparatory() {
            var f = ConversationRiskProcessor.extractScoreFactors(List.of("遗书"));
            assertThat(f.cssrsBehavior()).isEqualTo(RiskScoreCalculator.CssrsBehavior.PREPARATORY);
            assertThat(f.cssrsIdeation()).isEqualTo(RiskScoreCalculator.CssrsIdeation.WITH_PLAN_INTENT);
        }

        @Test
        @DisplayName("空关键词（语义层路径）→ 全零不越权")
        void emptyKeywords_allZero() {
            var f = ConversationRiskProcessor.extractScoreFactors(List.of());
            assertThat(f.intentWeight()).isZero();
            assertThat(f.planWeight()).isZero();
            assertThat(f.cssrsIdeation()).isNull();
            assertThat(f.cssrsBehavior()).isNull();
        }

        @Test
        @DisplayName("persistRiskEvent 使用抽取因子填充 ScoreInput（不再恒 0）")
        void persistEvent_usesExtractedFactors() {
            SessionState session = new SessionState(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    "sad", "web", "male", null, 4);
            RiskDetectionResult risk = new RiskDetectionResult(
                    RiskLevel.RED, "self_harm", List.of("想死", "割腕"), 90, true, "立即通知");
            when(riskScoreCalculator.calculate(any())).thenReturn(
                    new RiskScoreCalculator.ScoreResult(95, RiskLevel.RED, List.of("test")));

            processor.persistRiskEvent(session, risk);

            ArgumentCaptor<RiskScoreCalculator.ScoreInput> captor =
                    ArgumentCaptor.forClass(RiskScoreCalculator.ScoreInput.class);
            verify(riskScoreCalculator).calculate(captor.capture());
            assertThat(captor.getValue().intentWeight()).isEqualTo(15);
            assertThat(captor.getValue().planWeight()).isEqualTo(5);
            assertThat(captor.getValue().cssrsIdeation())
                    .isEqualTo(RiskScoreCalculator.CssrsIdeation.WITH_METHOD);
        }
    }

    @Nested
    @DisplayName("情绪建议文案")
    class EmotionSuggestion {

        @Test
        @DisplayName("各情绪类型返回对应建议")
        void returnsAppropriateSuggestion() {
            // B6 收编后以 DC-008 权威词表（EmotionVocabulary.ZH_LABELS）为准：
            // sad→难过、angry→生气（原本地 switch 文案已废弃）；unknown→未知（补齐码值）
            assertThat(processor.buildEmotionSuggestion("sad")).contains("难过");
            assertThat(processor.buildEmotionSuggestion("fearful")).contains("恐惧");
            assertThat(processor.buildEmotionSuggestion("angry")).contains("生气");
            assertThat(processor.buildEmotionSuggestion("unknown")).contains("未知");
        }
    }
}
