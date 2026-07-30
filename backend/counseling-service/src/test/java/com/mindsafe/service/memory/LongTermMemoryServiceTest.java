package com.mindsafe.service.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindsafe.ai.chat.AiChatService;
import com.mindsafe.domain.entity.LongTermMemory;
import com.mindsafe.domain.mapper.LongTermMemoryMapper;
import com.mindsafe.service.profile.MemoryProfileBackfillService;
import com.mindsafe.service.profile.MemoryProfileBackfillService.MemoryEvent;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LongTermMemoryService 单元测试（AI-008 / MEM-101）
 * <p>
 * 覆盖：提取落库后回注透传 event_type/person_role、幂等跳过、
 * LLM 返回空不落库不回注。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LongTermMemoryServiceTest {

    @Mock
    private LongTermMemoryMapper memoryMapper;

    @Mock
    private AiChatService aiChatService;

    @Mock
    private MemoryProfileBackfillService backfillService;

    @Mock
    private MemoryRiskCorrelator memoryRiskCorrelator;

    @Mock
    private MemoryRelevanceScorer memoryRelevanceScorer;

    @Mock
    private ThemeEvolutionEngine themeEvolutionEngine;

    private LongTermMemoryService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID studentUserId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new LongTermMemoryService(
                memoryMapper, aiChatService, new ObjectMapper(), backfillService,
                memoryRiskCorrelator, memoryRelevanceScorer, themeEvolutionEngine);
        // selectCount 两处调用（幂等检查 + evict），返回 0 同时满足
        when(memoryMapper.selectCount(any())).thenReturn(0L);
    }

    @Test
    @DisplayName("提取落库后调用 backfill，event_type/person_role/emotion 正确透传")
    void extractStoresMemoriesAndBackfillsProfile() {
        when(aiChatService.extractKeyEvents(anyString(), any())).thenReturn("""
                {"key_events":[
                  {"content":"第一次主动分享了学校的烦恼","emotion_context":"委屈",
                   "importance":0.8,"event_type":"milestone"},
                  {"content":"和妈妈约定每天聊十分钟","emotion_context":"开心",
                   "importance":0.7,"event_type":"person","person_role":"妈妈"}
                ]}
                """);

        service.extractAndStoreKeyEvents(tenantId, studentUserId, sessionId, "对话文本", null);

        verify(memoryMapper, times(2)).insert(any(LongTermMemory.class));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MemoryEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(backfillService).backfill(eq(tenantId), eq(studentUserId), captor.capture());
        List<MemoryEvent> events = captor.getValue();
        assertThat(events).hasSize(2);
        assertThat(events.get(0).eventType()).isEqualTo("milestone");
        assertThat(events.get(0).content()).isEqualTo("第一次主动分享了学校的烦恼");
        assertThat(events.get(0).personRole()).isNull();
        assertThat(events.get(0).emotionContext()).isEqualTo("委屈");
        assertThat(events.get(1).eventType()).isEqualTo("person");
        assertThat(events.get(1).personRole()).isEqualTo("妈妈");
    }

    @Test
    @DisplayName("幂等：同会话已提取过则不调 LLM 不回注")
    void skipsWhenSessionAlreadyExtracted() {
        when(memoryMapper.selectCount(any())).thenReturn(2L);

        service.extractAndStoreKeyEvents(tenantId, studentUserId, sessionId, "对话文本", null);

        verify(aiChatService, never()).extractKeyEvents(anyString(), any());
        verify(memoryMapper, never()).insert(any(LongTermMemory.class));
        verify(backfillService, never()).backfill(any(), any(), anyList());
    }

    @Test
    @DisplayName("LLM 返回空/空数组：不落库不回注")
    void emptyExtractionSkipsStoreAndBackfill() {
        when(aiChatService.extractKeyEvents(anyString(), any())).thenReturn("{\"key_events\":[]}");

        service.extractAndStoreKeyEvents(tenantId, studentUserId, sessionId, "对话文本", null);

        verify(memoryMapper, never()).insert(any(LongTermMemory.class));
        verify(backfillService, never()).backfill(any(), any(), anyList());
    }
}
