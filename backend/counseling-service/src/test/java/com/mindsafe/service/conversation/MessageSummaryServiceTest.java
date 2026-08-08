package com.mindsafe.service.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindsafe.ai.chat.AiChatService;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.MessageSummary;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.domain.mapper.MessageSummaryMapper;
import com.mindsafe.domain.util.MessageSummarySummarizer;
import com.mindsafe.service.memory.LongTermMemoryService;
import com.mindsafe.service.profile.ProfileExtractorService;
import com.mindsafe.service.quality.ConversationQualityService;
import com.mindsafe.service.security.FieldEncryptionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MessageSummaryService 单元测试（O 专题 S1：双 LLM 提炼合并为单次调用编排）。
 * <p>
 * 覆盖：会话结束仅一次提炼 LLM 调用 / 双节点 JSON 分发（画像+记忆）/
 * profile_patch 缺失跳过画像路 / LLM 异常静默降级（摘要已落库不上抛）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("消息摘要服务（S1 双 LLM 合并编排）")
class MessageSummaryServiceTest {

    @Mock private MessageSummaryMapper messageSummaryMapper;
    @Mock private CounselingSessionMapper sessionMapper;
    @Mock private AiChatService aiChatService;
    @Mock private FieldEncryptionService fieldEncryptionService;
    @Mock private ConversationQualityService conversationQualityService;
    @Mock private ProfileExtractorService profileExtractorService;
    @Mock private LongTermMemoryService longTermMemoryService;

    private MessageSummaryService service;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final UUID tenantId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID studentUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new MessageSummaryService(messageSummaryMapper, sessionMapper, aiChatService,
                fieldEncryptionService, conversationQualityService, profileExtractorService,
                longTermMemoryService, new ObjectMapper(), registry);

        // BA-04：实体工厂已删，测试数据手动装配（setter 方式）
        MessageSummary student = new MessageSummary();
        student.setTenantId(tenantId);
        student.setSessionId(sessionId);
        student.setStudentUserId(studentUserId);
        student.setTurnCount(1);
        student.setSenderType("student");
        student.setContentSummary("我考试前特别紧张");
        student.setEmotionTags("[\"anxious\"]");
        MessageSummary ai = new MessageSummary();
        ai.setTenantId(tenantId);
        ai.setSessionId(sessionId);
        ai.setStudentUserId(studentUserId);
        ai.setTurnCount(2);
        ai.setSenderType("ai");
        ai.setContentSummary("紧张的时候身体有什么感觉？");
        when(messageSummaryMapper.selectList(any())).thenReturn(List.of(student, ai));
        when(fieldEncryptionService.decrypt(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(fieldEncryptionService.encrypt(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(aiChatService.generateSessionSummary(anyString())).thenReturn("{\"mainTopic\":\"考试焦虑\"}");
    }

    @Test
    @DisplayName("ARCH-010 P2-5：摘要 LLM 失败必须产生 metrics 计数（stage=summary）且不上抛")
    void summaryFailure_incrementsMetric() {
        when(aiChatService.generateSessionSummary(anyString()))
                .thenThrow(new RuntimeException("llm down"));

        assertThatCode(() -> service.generateSummaryAsync(tenantId, sessionId, studentUserId))
                .doesNotThrowAnyException();
        assertThat(registry.counter("mindsafe.pipeline.failure", "stage", "summary").count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("S1: 会话结束只调用一次提炼 LLM（合并双节点，替代两次独立调用）")
    void singleInsightCall() {
        when(aiChatService.extractConversationInsights(anyString(), anyString()))
                .thenReturn("{\"profile_patch\":{\"communication_pref\":{\"preferred_style\":\"expressive\"}},"
                        + "\"key_events\":[]}");

        service.generateSummaryAsync(tenantId, sessionId, studentUserId);

        verify(aiChatService, times(1)).extractConversationInsights(anyString(), anyString());
        verify(sessionMapper).updateById(any(CounselingSession.class));
    }

    @Test
    @DisplayName("S1: 合并结果双节点分发（画像与关键事件各自落库）")
    void dualNodeDispatch() {
        when(aiChatService.extractConversationInsights(anyString(), anyString()))
                .thenReturn("{\"profile_patch\":{\"communication_pref\":{\"preferred_style\":\"expressive\"}},"
                        + "\"key_events\":[{\"content\":\"第一次主动分享考试焦虑\",\"emotion_context\":\"焦虑\","
                        + "\"importance\":0.8,\"event_type\":\"milestone\"}]}");

        service.generateSummaryAsync(tenantId, sessionId, studentUserId);

        verify(profileExtractorService).extractAndMerge(eq(tenantId), eq(studentUserId), any());
        verify(longTermMemoryService).extractAndStoreKeyEvents(eq(tenantId), eq(studentUserId), eq(sessionId), any());
    }

    @Test
    @DisplayName("S1: profile_patch 缺失 → 画像路跳过，关键事件路照常")
    void missingProfilePatchSkipsProfile() {
        when(aiChatService.extractConversationInsights(anyString(), anyString()))
                .thenReturn("{\"key_events\":[{\"content\":\"测试事件\",\"importance\":0.5}]}");

        service.generateSummaryAsync(tenantId, sessionId, studentUserId);

        verify(profileExtractorService, never()).extractAndMerge(any(), any(), any());
        verify(longTermMemoryService).extractAndStoreKeyEvents(eq(tenantId), eq(studentUserId), eq(sessionId), any());
    }

    @Test
    @DisplayName("S1: LLM 提炼异常 → 摘要已落库，异常不上抛（静默降级）")
    void llmFailureSilentlyDegraded() {
        when(aiChatService.extractConversationInsights(anyString(), anyString()))
                .thenThrow(new RuntimeException("llm down"));

        service.generateSummaryAsync(tenantId, sessionId, studentUserId);

        verify(sessionMapper).updateById(any(CounselingSession.class));
        verify(profileExtractorService, never()).extractAndMerge(any(), any(), any());
        verify(longTermMemoryService, never()).extractAndStoreKeyEvents(any(), any(), any(), any());
    }

    // ===== BA-04（DOC-074）：D-7 两级摘要策略上移 service 单一入口 =====

    @Test
    @DisplayName("BA-04: 学生消息 risk≥2 原文保真，超长截断至 1024（非提炼）")
    void persistStudent_highRiskKeepsFullText() {
        SessionState session = new SessionState(sessionId, tenantId, studentUserId, "焦虑", "web", null, null, 0);

        service.persistStudentMessageSummary(session, 1, "a".repeat(2000), "焦虑", 2);

        verify(fieldEncryptionService).encrypt("a".repeat(1024));
        ArgumentCaptor<MessageSummary> captor = ArgumentCaptor.forClass(MessageSummary.class);
        verify(messageSummaryMapper).insert(captor.capture());
        assertThat(captor.getValue().getRiskSignals()).isEqualTo("[{\"level\":2}]");
    }

    @Test
    @DisplayName("BA-04: 学生消息 risk<2 语义提炼至 ≤200 字（去除句尾语气词）")
    void persistStudent_normalRiskSummarized() {
        SessionState session = new SessionState(sessionId, tenantId, studentUserId, "平静", "web", null, null, 0);
        String text = "嗯嗯。我今天考试没考好呀。好烦啊。";

        service.persistStudentMessageSummary(session, 1, text, null, 1);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(fieldEncryptionService).encrypt(captor.capture());
        assertThat(captor.getValue().length()).isLessThanOrEqualTo(MessageSummarySummarizer.MAX_SUMMARY_LENGTH);
        assertThat(captor.getValue()).contains("我今天考试没考好").doesNotContain("嗯嗯");
    }

    @Test
    @DisplayName("BA-04: JSON 字段经 ObjectMapper 装配（emotionTags/riskSignals/topicTags）")
    void persistStudent_jsonFieldsAssembled() {
        SessionState session = new SessionState(sessionId, tenantId, studentUserId, "焦虑", "web", null, null, 0);

        service.persistStudentMessageSummary(session, 3, "内容", "焦虑", 2);

        ArgumentCaptor<MessageSummary> captor = ArgumentCaptor.forClass(MessageSummary.class);
        verify(messageSummaryMapper).insert(captor.capture());
        MessageSummary m = captor.getValue();
        assertThat(m.getSenderType()).isEqualTo("student");
        assertThat(m.getTurnCount()).isEqualTo(3);
        assertThat(m.getEmotionTags()).isEqualTo("[\"焦虑\"]");
        assertThat(m.getTopicTags()).isEqualTo("[]");
        assertThat(m.getStudentUserId()).isEqualTo(studentUserId);
    }

    @Test
    @DisplayName("BA-04: risk=0 学生消息 riskSignals 空数组")
    void persistStudent_zeroRiskEmptySignals() {
        SessionState session = new SessionState(sessionId, tenantId, studentUserId, "开心", "web", null, null, 0);

        service.persistStudentMessageSummary(session, 1, "今天很开心", "开心", 0);

        ArgumentCaptor<MessageSummary> captor = ArgumentCaptor.forClass(MessageSummary.class);
        verify(messageSummaryMapper).insert(captor.capture());
        assertThat(captor.getValue().getRiskSignals()).isEqualTo("[]");
    }

    @Test
    @DisplayName("BA-04: AI 摘要恒 risk=0，超长截断 1024，JSON 字段空数组")
    void persistAi_truncatesAndEmptyJson() {
        SessionState session = new SessionState(sessionId, tenantId, studentUserId, "平静", "web", null, null, 0);

        service.persistAiMessageSummary(session, 2, "b".repeat(2000));

        verify(fieldEncryptionService).encrypt("b".repeat(1024));
        ArgumentCaptor<MessageSummary> captor = ArgumentCaptor.forClass(MessageSummary.class);
        verify(messageSummaryMapper).insert(captor.capture());
        MessageSummary m = captor.getValue();
        assertThat(m.getSenderType()).isEqualTo("ai");
        assertThat(m.getRiskLevel()).isZero();
        assertThat(m.getEmotionTags()).isEqualTo("[]");
        assertThat(m.getRiskSignals()).isEqualTo("[]");
    }

    @Test
    @DisplayName("BA-04: AI 回复空白 → 不落库")
    void persistAi_blankSkipsInsert() {
        SessionState session = new SessionState(sessionId, tenantId, studentUserId, "平静", "web", null, null, 0);

        service.persistAiMessageSummary(session, 2, "   ");

        verify(messageSummaryMapper, never()).insert(any(MessageSummary.class));
    }
}
