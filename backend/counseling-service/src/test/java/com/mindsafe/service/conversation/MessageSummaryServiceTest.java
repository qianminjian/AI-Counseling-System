package com.mindsafe.service.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindsafe.ai.chat.AiChatService;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.MessageSummary;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.domain.mapper.MessageSummaryMapper;
import com.mindsafe.service.memory.LongTermMemoryService;
import com.mindsafe.service.profile.ProfileExtractorService;
import com.mindsafe.service.quality.ConversationQualityService;
import com.mindsafe.service.security.FieldEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.UUID;

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

    private final UUID tenantId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID studentUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new MessageSummaryService(messageSummaryMapper, sessionMapper, aiChatService,
                fieldEncryptionService, conversationQualityService, profileExtractorService,
                longTermMemoryService, new ObjectMapper());

        MessageSummary student = MessageSummary.studentMessage(
                tenantId, sessionId, studentUserId, 1, "我考试前特别紧张", "anxious", 1);
        MessageSummary ai = MessageSummary.aiMessage(
                tenantId, sessionId, studentUserId, 2, "紧张的时候身体有什么感觉？");
        when(messageSummaryMapper.selectList(any())).thenReturn(List.of(student, ai));
        when(fieldEncryptionService.decrypt(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(aiChatService.generateSessionSummary(anyString())).thenReturn("{\"mainTopic\":\"考试焦虑\"}");
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
}
