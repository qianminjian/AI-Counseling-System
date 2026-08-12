package com.mindsafe.service.memory;

import com.mindsafe.ai.risk.RiskKeywordRegistry;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindsafe.domain.entity.LongTermMemory;
import com.mindsafe.domain.mapper.LongTermMemoryMapper;
import com.mindsafe.service.profile.MemoryProfileBackfillService;
import com.mindsafe.service.profile.MemoryProfileBackfillService.MemoryEvent;
import com.mindsafe.service.notification.RiskNotifyOutboxService;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LongTermMemoryService 单元测试（AI-008 / MEM-101）
 * <p>
 * O 专题 S1 后：LLM 提炼调用已上移至 MessageSummaryService 编排，
 * 本服务直接接收已解析的 JsonNode key_events。
 * 覆盖：提取落库后回注透传 event_type/person_role、幂等跳过、
 * 空事件数组不落库不回注。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LongTermMemoryServiceTest {

    @Mock
    private LongTermMemoryMapper memoryMapper;

    @Mock
    private MemoryProfileBackfillService backfillService;

    @Mock
    private MemoryRiskCorrelator memoryRiskCorrelator;

    @Mock
    private MemoryRelevanceScorer memoryRelevanceScorer;

    @Mock
    private ThemeEvolutionEngine themeEvolutionEngine;

    @Mock
    private com.mindsafe.domain.mapper.RiskEventMapper riskEventMapper;

    @Mock
    private RiskNotifyOutboxService riskNotifyOutboxService;

    private LongTermMemoryService service;

    private final ObjectMapper om = new ObjectMapper();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final UUID tenantId = UUID.randomUUID();
    private final UUID studentUserId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new LongTermMemoryService(
                memoryMapper, backfillService,
                memoryRiskCorrelator, memoryRelevanceScorer, themeEvolutionEngine, riskEventMapper,
                riskNotifyOutboxService, registry, new RiskKeywordRegistry());
        // selectCount 两处调用（幂等检查 + evict），返回 0 同时满足
        when(memoryMapper.selectCount(any())).thenReturn(0L);
    }

    private JsonNode events(String json) throws Exception {
        return om.readTree(json);
    }

    @Test
    @DisplayName("ARCH-010 P2-5：记忆写入失败必须产生 metrics 计数（stage=memory）且不上抛")
    void storeFailure_incrementsMetric() throws Exception {
        when(memoryMapper.insert(any(LongTermMemory.class))).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> service.extractAndStoreKeyEvents(tenantId, studentUserId, sessionId,
                events("""
                {"key_events":[
                  {"content":"第一次主动分享","emotion_context":"委屈","importance":0.8}
                ]}
                """)))
                .doesNotThrowAnyException();
        assertThat(registry.counter("mindsafe.pipeline.failure", "stage", "memory").count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("提取落库后调用 backfill，event_type/person_role/emotion 正确透传")
    void extractStoresMemoriesAndBackfillsProfile() throws Exception {
        service.extractAndStoreKeyEvents(tenantId, studentUserId, sessionId, events("""
                {"key_events":[
                  {"content":"第一次主动分享了学校的烦恼","emotion_context":"委屈",
                   "importance":0.8,"event_type":"milestone"},
                  {"content":"和妈妈约定每天聊十分钟","emotion_context":"开心",
                   "importance":0.7,"event_type":"person","person_role":"妈妈"}
                ]}
                """));

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
    @DisplayName("幂等：同会话已提取过则跳过存储与回注")
    void skipsWhenSessionAlreadyExtracted() throws Exception {
        when(memoryMapper.selectCount(any())).thenReturn(2L);

        service.extractAndStoreKeyEvents(tenantId, studentUserId, sessionId, events("""
                {"key_events":[{"content":"不应入库","importance":0.9}]}
                """));

        verify(memoryMapper, never()).insert(any(LongTermMemory.class));
        verify(backfillService, never()).backfill(any(), any(), anyList());
    }

    @Test
    @DisplayName("空事件数组：不落库不回注")
    void emptyExtractionSkipsStoreAndBackfill() throws Exception {
        service.extractAndStoreKeyEvents(tenantId, studentUserId, sessionId, events("{\"key_events\":[]}"));

        verify(memoryMapper, never()).insert(any(LongTermMemory.class));
        verify(backfillService, never()).backfill(any(), any(), anyList());
    }

    @Test
    @DisplayName("C1: 召回计数批量更新——一次 update 替代 N 次 updateById（N+1 消除）")
    void recallCountUpdate_batched() {
        List<LongTermMemory> memories = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            LongTermMemory m = new LongTermMemory();
            m.setMemoryId(UUID.randomUUID());
            m.setImportance(0.8f);
            m.setCreatedAt(Instant.now().minusSeconds(i));
            m.setRecallCount(0);
            memories.add(m);
        }
        when(memoryMapper.selectPage(any(), any())).thenReturn(new Page<LongTermMemory>().setRecords(memories));
        when(memoryRelevanceScorer.score(anyFloat(), anyFloat(), any(), any(), any())).thenReturn(10.0);
        when(memoryRelevanceScorer.isWorthRecalling(anyDouble())).thenReturn(true);

        String prompt = service.buildMemoryPrompt(tenantId, studentUserId);

        assertThat(prompt).as("召回 Prompt 应正常生成").isNotNull();
        // C1（2026-08-05）：召回计数用一条批量 update 替代 N 次 updateById
        verify(memoryMapper, times(1)).update(any(), any());
        verify(memoryMapper, never()).updateById(any(LongTermMemory.class));
    }

    @Test
    @DisplayName("C1: 遗忘淘汰批量删除——一次 deleteBatchIds 替代 N 次 deleteById（N+1 消除）")
    void evictDecision_batchedDelete() throws Exception {
        JsonNode eventsNode = events("""
                {"key_events":[{"content":"测试事件","importance":0.5}]}
                """);
        // 10 条存量记忆，其中前 3 条命中遗忘决策
        List<LongTermMemory> allMemories = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            LongTermMemory m = new LongTermMemory();
            m.setMemoryId(UUID.randomUUID());
            m.setImportance(0.3f);
            m.setCreatedAt(Instant.now().minusSeconds(1000L + i));
            m.setRecallCount(0);
            allMemories.add(m);
        }
        Set<String> forgetIds = allMemories.stream().limit(3)
                .map(m -> m.getMemoryId().toString())
                .collect(Collectors.toSet());
        when(memoryMapper.selectList(any())).thenReturn(allMemories);
        when(memoryRiskCorrelator.evaluateForget(any(), any())).thenAnswer(inv -> {
            MemoryRiskCorrelator.MemoryEntry entry = inv.getArgument(0);
            return new MemoryRiskCorrelator.ForgetDecision(
                    forgetIds.contains(entry.memoryId()), "test", "delete");
        });

        service.extractAndStoreKeyEvents(tenantId, studentUserId, sessionId, eventsNode);

        verify(memoryMapper, times(1)).deleteBatchIds(anyList());
        verify(memoryMapper, never()).deleteById(any(UUID.class));
    }

    @Test
    @DisplayName("C1: 数量兜底淘汰批量删除——survivors 一次 deleteBatchIds，不逐条 deleteById")
    void evictOverflow_batchedDelete() throws Exception {
        JsonNode eventsNode = events("""
                {"key_events":[{"content":"测试事件","importance":0.5}]}
                """);
        // 61 条存量记忆（超过 MAX_MEMORIES_PER_STUDENT=50），全部不命中遗忘决策
        List<LongTermMemory> allMemories = new ArrayList<>();
        for (int i = 0; i < 61; i++) {
            LongTermMemory m = new LongTermMemory();
            m.setMemoryId(UUID.randomUUID());
            m.setImportance(0.3f);
            m.setCreatedAt(Instant.now().minusSeconds(1000L + i));
            m.setRecallCount(0);
            allMemories.add(m);
        }
        // 存量 61 条走 selectList（evictOldMemories）；数量兜底 excess=11 条幸存者走 selectPage（AUD-043）
        when(memoryMapper.selectList(any())).thenReturn(allMemories);
        when(memoryMapper.selectPage(any(), any())).thenReturn(
                new Page<LongTermMemory>().setRecords(allMemories.subList(0, 11)));
        when(memoryRiskCorrelator.evaluateForget(any(), any()))
                .thenReturn(new MemoryRiskCorrelator.ForgetDecision(false, "keep", "none"));

        service.extractAndStoreKeyEvents(tenantId, studentUserId, sessionId, eventsNode);

        verify(memoryMapper, atLeastOnce()).deleteBatchIds(anyList());
        verify(memoryMapper, never()).deleteById(any(UUID.class));
    }
}
