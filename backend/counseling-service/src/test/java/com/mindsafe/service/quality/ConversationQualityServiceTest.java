package com.mindsafe.service.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindsafe.ai.chat.AiChatService;
import com.mindsafe.domain.entity.QualityScore;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.domain.mapper.QualityScoreMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ConversationQualityService 单元测试（ARCH-010 P2-5 补：该服务此前无单测）
 * <p>
 * 覆盖：异步评估失败必须产生 metrics 计数（stage=evaluation）且不上抛；
 * 抽样判定可覆写（默认 Math.random 不可控，测试子类强制选中）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConversationQualityServiceTest {

    @Mock private AiChatService aiChatService;
    @Mock private QualityScoreMapper qualityScoreMapper;
    @Mock private CounselingSessionMapper sessionMapper;
    @Mock private EmpathyStructureEvaluator empathyStructureEvaluator;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private ConversationQualityService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();

    /** 强制选中抽样（默认 Math.random 不可控），便于确定性测试评估失败/成功路径 */
    private static class TestableQualityService extends ConversationQualityService {
        TestableQualityService(AiChatService aiChatService, QualityScoreMapper qualityScoreMapper,
                               CounselingSessionMapper sessionMapper,
                               EmpathyStructureEvaluator empathyStructureEvaluator,
                               ObjectMapper objectMapper, SimpleMeterRegistry registry) {
            super(aiChatService, qualityScoreMapper, sessionMapper, empathyStructureEvaluator,
                    objectMapper, registry);
        }

        @Override
        boolean shouldEvaluate() {
            return true;
        }
    }

    @BeforeEach
    void setUp() {
        service = new TestableQualityService(aiChatService, qualityScoreMapper, sessionMapper,
                empathyStructureEvaluator, objectMapper, registry);
    }

    @Test
    @DisplayName("ARCH-010 P2-5：评估 LLM 失败必须产生 metrics 计数（stage=evaluation）且不上抛")
    void evaluationFailure_incrementsMetric() {
        when(aiChatService.evaluateConversationQuality(anyString()))
                .thenThrow(new RuntimeException("llm down"));

        assertThatCode(() -> service.evaluateSessionAsync(tenantId, sessionId, "学生说最近压力大"))
                .doesNotThrowAnyException();
        assertThat(registry.counter("mindsafe.pipeline.failure", "stage", "evaluation").count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("正常评估：落库 QualityScore，不产生失败计数")
    void evaluateSuccess_persistsAndNoFailureMetric() {
        when(qualityScoreMapper.selectOne(any())).thenReturn(null);
        when(aiChatService.evaluateConversationQuality(anyString()))
                .thenReturn("{\"empathy_score\":0.8,\"cbt_completion\":0.7,\"safety_compliance\":0.9,\"engagement_score\":0.6}");

        QualityScore score = service.evaluateSession(tenantId, sessionId, "学生 说最近压力大");

        assertThat(score).isNotNull();
        assertThat(score.getOverallScore()).isNotNull();
        verify(qualityScoreMapper).insert(any(QualityScore.class));
        assertThat(registry.counter("mindsafe.pipeline.failure", "stage", "evaluation").count())
                .isZero();
    }

    @Test
    @DisplayName("空文本 → null 不调 LLM")
    void evaluateSession_blankText() {
        assertThat(service.evaluateSession(tenantId, sessionId, "   ")).isNull();
        org.mockito.Mockito.verifyNoInteractions(aiChatService);
    }

    @Test
    @DisplayName("幂等：已评估会话直接返回既有记录")
    void evaluateSession_idempotent() {
        QualityScore existing = new QualityScore();
        existing.setSessionId(sessionId);
        when(qualityScoreMapper.selectOne(any())).thenReturn(existing);

        QualityScore score = service.evaluateSession(tenantId, sessionId, "对话内容");

        assertThat(score).isSameAs(existing);
        org.mockito.Mockito.verifyNoInteractions(aiChatService);
    }

    @Test
    @DisplayName("LLM 返回空 → null")
    void evaluateSession_emptyJudgeResult() {
        when(qualityScoreMapper.selectOne(any())).thenReturn(null);
        when(aiChatService.evaluateConversationQuality(anyString())).thenReturn("  ");

        assertThat(service.evaluateSession(tenantId, sessionId, "对话")).isNull();
    }

    @Test
    @DisplayName("LLM 返回非法 JSON → null（解析失败静默）")
    void evaluateSession_unparsable() {
        when(qualityScoreMapper.selectOne(any())).thenReturn(null);
        when(aiChatService.evaluateConversationQuality(anyString())).thenReturn("not json");

        assertThat(service.evaluateSession(tenantId, sessionId, "对话")).isNull();
    }

    @Test
    @DisplayName("低分（overall<0.4）自动标记 flag + 原因")
    void evaluateSession_lowScoreFlagged() {
        when(qualityScoreMapper.selectOne(any())).thenReturn(null);
        when(aiChatService.evaluateConversationQuality(anyString()))
                .thenReturn("{\"empathy_score\":0.1,\"cbt_completion\":0.1,\"safety_compliance\":0.1,\"engagement_score\":0.1}");

        QualityScore score = service.evaluateSession(tenantId, sessionId, "学生说不想说话");

        assertThat(score.getFlagged()).isTrue();
        assertThat(score.getFlagReason()).contains("低于阈值");
        assertThat(score.getEvaluator()).isEqualTo("llm-judge");
        assertThat(score.getRawResponse()).isNotBlank();
        assertThat(score.getEvaluatedAt()).isNotNull();
    }

    @Test
    @DisplayName("code fence 包裹的 JSON 正常解析（``` 剥离）")
    void evaluateSession_codeFence() {
        when(qualityScoreMapper.selectOne(any())).thenReturn(null);
        when(aiChatService.evaluateConversationQuality(anyString()))
                .thenReturn("```json\n{\"empathy_score\":0.5,\"safety_compliance\":0.8}\n```");

        QualityScore score = service.evaluateSession(tenantId, sessionId, "对话");

        assertThat(score).isNotNull();
        assertThat(score.getEmpathyScore()).isEqualByComparingTo("0.5");
        // 缺字段不参与加权（weightSum 仅 60）：overall = (0.5*0.25+0.8*0.35)/0.6
        assertThat(score.getOverallScore()).isEqualByComparingTo("0.675");
        assertThat(score.getFlagged()).isFalse();
    }

    @Test
    @DisplayName("越界分数（>1）视为无效字段 → null")
    void readScore_outOfRange() {
        when(qualityScoreMapper.selectOne(any())).thenReturn(null);
        when(aiChatService.evaluateConversationQuality(anyString()))
                .thenReturn("{\"empathy_score\":2.5,\"safety_compliance\":0.8}");

        QualityScore score = service.evaluateSession(tenantId, sessionId, "对话");

        assertThat(score.getEmpathyScore()).isNull();
        assertThat(score.getSafetyCompliance()).isNotNull();
    }

    @Test
    @DisplayName("抽样跳过：shouldEvaluate=false 时不评估不落库")
    void evaluateSessionAsync_skipped() {
        ConversationQualityService skipService = new ConversationQualityService(
                aiChatService, qualityScoreMapper, sessionMapper, empathyStructureEvaluator, objectMapper, registry) {
            @Override
            boolean shouldEvaluate() {
                return false;
            }
        };

        skipService.evaluateSessionAsync(tenantId, sessionId, "对话");

        org.mockito.Mockito.verifyNoInteractions(aiChatService, qualityScoreMapper);
    }

    @Test
    @DisplayName("EMP-201：AI 回复行提取 + 共情结构补充（LLM 高分但规则低分记录差异）")
    void evaluateSession_empathySupplement() {
        when(qualityScoreMapper.selectOne(any())).thenReturn(null);
        when(aiChatService.evaluateConversationQuality(anyString()))
                .thenReturn("{\"empathy_score\":0.9,\"safety_compliance\":0.8}");
        EmpathyStructureEvaluator.SessionEmpathySummary lowSummary =
                new EmpathyStructureEvaluator.SessionEmpathySummary(2, 0.1, 1, 1, 0.2);
        when(empathyStructureEvaluator.summarizeSession(any())).thenReturn(lowSummary);

        QualityScore score = service.evaluateSession(tenantId, sessionId,
                "学生：我今天很难过\n波波：听起来你很难过，愿意多聊聊吗？\n助手：已记录");

        assertThat(score).isNotNull();
        verify(empathyStructureEvaluator).summarizeSession(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    @DisplayName("EMP-201：无 AI 前缀回复行 → 跳过结构评估")
    void evaluateSession_empathyNoAiLines() {
        when(qualityScoreMapper.selectOne(any())).thenReturn(null);
        when(aiChatService.evaluateConversationQuality(anyString()))
                .thenReturn("{\"empathy_score\":0.5}");

        QualityScore score = service.evaluateSession(tenantId, sessionId, "学生：随便聊聊\n学生：再聊几句");

        assertThat(score).isNotNull();
        org.mockito.Mockito.verifyNoInteractions(empathyStructureEvaluator);
    }
}
