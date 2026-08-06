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

        QualityScore score = service.evaluateSession(tenantId, sessionId, "学生说最近压力大");

        assertThat(score).isNotNull();
        assertThat(score.getOverallScore()).isNotNull();
        verify(qualityScoreMapper).insert(any(QualityScore.class));
        assertThat(registry.counter("mindsafe.pipeline.failure", "stage", "evaluation").count())
                .isZero();
    }
}
