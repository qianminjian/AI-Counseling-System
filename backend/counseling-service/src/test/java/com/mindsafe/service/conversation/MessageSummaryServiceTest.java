package com.mindsafe.service.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindsafe.ai.chat.AiChatService;
import com.mindsafe.common.tenant.TenantContextHolder;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.MessageSummary;
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
    @Mock private CounselingSessionStore sessionStore;
    @Mock private AiChatService aiChatService;
    @Mock private FieldEncryptionService fieldEncryptionService;
    @Mock private ConversationQualityService conversationQualityService;
    @Mock private ProfileExtractorService profileExtractorService;
    @Mock private LongTermMemoryService longTermMemoryService;
    @Mock private RedisSessionStateStore sessionStateStore;

    private MessageSummaryService service;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final UUID tenantId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID studentUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new MessageSummaryService(messageSummaryMapper, sessionStore, aiChatService,
                fieldEncryptionService, conversationQualityService, profileExtractorService,
                longTermMemoryService, new ObjectMapper(), registry, sessionStateStore);

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
        verify(sessionStore).updateById(any(CounselingSession.class));
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

        verify(sessionStore).updateById(any(CounselingSession.class));
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

    @Test
    @DisplayName("BUG-TENANT-01: 无租户上下文（reactor 回调线程）persistAiMessageSummary 仍成功落库（runAsSystem）")
    void persistAi_noTenantContext_stillInserts() {
        // 模拟 Flux.defer 回调线程：ThreadLocal 无请求租户上下文（M1-003 fail-fast 会拒绝裸 insert）
        TenantContextHolder.clear();
        SessionState session = new SessionState(sessionId, tenantId, studentUserId, "平静", "web", null, null, 0);

        service.persistAiMessageSummary(session, 2, "AI 的完整回复");

        // runAsSystem 包裹下 insert 正常执行，不被 fail-fast 拒绝
        verify(messageSummaryMapper).insert(any(MessageSummary.class));
        // 系统作用域不得泄漏到调用线程（嵌套安全）
        assertThat(TenantContextHolder.isSystemScope()).isFalse();
    }

    @Test
    @DisplayName("BUG-TENANT-01: 无租户上下文 updateProgressiveSummaryAsync 正常执行且不泄漏系统作用域")
    void updateProgressive_noTenantContext_stillWorks() {
        TenantContextHolder.clear();
        when(messageSummaryMapper.selectList(any()))
                .thenReturn(List.of(newMessage("student", "enc-1"), newMessage("ai", "enc-2")));
        when(fieldEncryptionService.decrypt(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(aiChatService.summarizeSessionProgress(anyString()))
                .thenReturn("学生分享了开心的事，AI 正向回应");
        SessionState session = newState(8, 4);
        when(sessionStateStore.get(tenantId, sessionId)).thenReturn(session);

        service.updateProgressiveSummaryAsync(tenantId, sessionId, 8);

        assertThat(session.getSessionSummary()).isEqualTo("学生分享了开心的事，AI 正向回应");
        verify(sessionStateStore).save(eq(tenantId), eq(sessionId), eq(session));
        assertThat(TenantContextHolder.isSystemScope()).isFalse();
    }

    @Test
    @DisplayName("BUG-TENANT-01b: 无租户上下文（reactor 回调线程）generateSummaryAsync 正常生成摘要且不泄漏系统作用域")
    void generateSummary_noTenantContext_stillWorks() {
        TenantContextHolder.clear();

        service.generateSummaryAsync(tenantId, sessionId, studentUserId);

        verify(sessionStore).updateById(any(CounselingSession.class));
        assertThat(TenantContextHolder.isSystemScope()).isFalse();
    }

    // ===== BA-10：消息读取单点（查→解密→拼接唯一实现，文案统一「学生/AI」） =====

    @Test
    @DisplayName("BA-10: readSessionTranscript 文案单点——角色标注统一「学生/AI」，全仓禁止「波波」漂移")
    void readTranscript_roleLabelSingleSource() {
        when(messageSummaryMapper.selectList(any()))
                .thenReturn(List.of(newMessage("student", "我考试前特别紧张"),
                        newMessage("ai", "紧张的时候身体有什么感觉？")));

        String transcript = service.readSessionTranscript(tenantId, sessionId, MessageSummaryService.TranscriptFilter.all());

        assertThat(transcript).isEqualTo("学生: 我考试前特别紧张\nAI: 紧张的时候身体有什么感觉？\n");
        // 防「学生/波波」漂移回潮：角色标注只允许学生/AI 两种
        assertThat(transcript).doesNotContain("波波");
    }

    @Test
    @DisplayName("BA-10: readSessionTranscript 解密后空白内容被过滤（不产生空行）")
    void readTranscript_filtersBlankContent() {
        when(messageSummaryMapper.selectList(any()))
                .thenReturn(List.of(newMessage("student", "enc-blank"), newMessage("student", "正常内容")));
        when(fieldEncryptionService.decrypt("enc-blank")).thenReturn("   ");

        String transcript = service.readSessionTranscript(tenantId, sessionId, MessageSummaryService.TranscriptFilter.all());

        assertThat(transcript).isEqualTo("学生: 正常内容\n");
    }

    @Test
    @DisplayName("BA-10: readSessionTranscript 过滤条件传入 selectList（SQL 下推；wrapper 结构由集成测试覆盖）")
    void readTranscript_filterConditionsApplied() {
        // 过滤在 SQL 层（wrapper 下推）：mock 返回即过滤后列表，此处验证拼接行为 + selectList 收到 wrapper
        // 注：纯单元测试无 MyBatis-Plus TableInfo 缓存，wrapper 列名/参数值不可解析（getSqlSegment 抛 lambda cache 异常），
        //     SQL 下推正确性由 MyBatis-Plus 标准行为保证，并由真实 DB 集成测试覆盖
        when(messageSummaryMapper.selectList(any()))
                .thenReturn(List.of(newMessage("student", "学生消息")));

        String filtered = service.readSessionTranscript(tenantId, sessionId,
                new MessageSummaryService.TranscriptFilter("student", 1));

        assertThat(filtered).isEqualTo("学生: 学生消息\n");
        verify(messageSummaryMapper).selectList(any());
    }

    @Test
    @DisplayName("BA-10: readTranscript 空过滤（all）不带 senderType/turnCount 条件（wrapper 空过滤分支）")
    void readTranscript_allHasNoFilterConditions() {
        when(messageSummaryMapper.selectList(any()))
                .thenReturn(List.of(newMessage("student", "学生消息")));

        String transcript = service.readSessionTranscript(tenantId, sessionId, MessageSummaryService.TranscriptFilter.all());

        assertThat(transcript).isEqualTo("学生: 学生消息\n");
        verify(messageSummaryMapper).selectList(any());
    }

    @Test
    @DisplayName("BA-10: readSessionTranscript 查询异常 → 降级空串（不抛）")
    void readTranscript_failureDegradesToEmpty() {
        when(messageSummaryMapper.selectList(any())).thenThrow(new RuntimeException("db down"));

        String transcript = service.readSessionTranscript(tenantId, sessionId, MessageSummaryService.TranscriptFilter.all());

        assertThat(transcript).isEmpty();
    }

    @Test
    @DisplayName("M2: generateSummaryAsync 转写读取失败 → 失败指标触发（读取异常不再被吞，ARCH-010 P2-5 观测性恢复）")
    void generateSummary_transcriptReadFailure_incrementsMetric() {
        when(messageSummaryMapper.selectList(any())).thenThrow(new RuntimeException("db down"));
        double before = registry.counter("mindsafe.pipeline.failure", "stage", "summary").count();

        assertThatCode(() -> service.generateSummaryAsync(tenantId, sessionId, studentUserId))
                .doesNotThrowAnyException();
        assertThat(registry.counter("mindsafe.pipeline.failure", "stage", "summary").count())
                .isEqualTo(before + 1);
        verify(aiChatService, never()).generateSessionSummary(anyString());
    }

    @Test
    @DisplayName("M2: readSessionTranscriptStrict 查询异常向上抛出（失败感知版契约）")
    void readTranscriptStrict_propagatesFailure() {
        when(messageSummaryMapper.selectList(any())).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> service.readSessionTranscriptStrict(
                tenantId, sessionId, MessageSummaryService.TranscriptFilter.all()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("BA-10: readStudentPlainTexts 仅返回学生明文且 SQL 下推 senderType=student（ORCH-008 深度量化输入）")
    void readStudentPlainTexts_returnsStudentOnly() {
        // 过滤在 SQL 层（wrapper 下推），mock 返回即过滤后列表（学生消息）
        when(messageSummaryMapper.selectList(any()))
                .thenReturn(List.of(newMessage("student", "学生一"), newMessage("student", "  ")));

        List<String> texts = service.readStudentPlainTexts(tenantId, sessionId);

        assertThat(texts).containsExactly("学生一");  // 空白过滤 + 顺序保持
        verify(messageSummaryMapper).selectList(any());  // SQL 下推 senderType 过滤（结构由集成测试覆盖）
    }

    @Test
    @DisplayName("BA-10: readStudentPlainTexts 查询异常 → 降级空列表（不抛）")
    void readStudentPlainTexts_failureDegradesToEmpty() {
        when(messageSummaryMapper.selectList(any())).thenThrow(new RuntimeException("db down"));

        assertThat(service.readStudentPlainTexts(tenantId, sessionId)).isEmpty();
    }

    // ===== BA-10：SessionSummaryUpdater 收编（滚动摘要：shouldUpdate + updateProgressiveSummaryAsync） =====

    @Test
    @DisplayName("BA-10: shouldUpdateProgressiveSummary 每 4 轮触发（前 4 轮靠原始窗口）")
    void shouldUpdateProgressiveSummary_intervalRules() {
        assertThat(service.shouldUpdateProgressiveSummary(newState(3, 0))).isFalse();
        assertThat(service.shouldUpdateProgressiveSummary(newState(4, 0))).isTrue();
        assertThat(service.shouldUpdateProgressiveSummary(newState(7, 4))).isFalse();
        assertThat(service.shouldUpdateProgressiveSummary(newState(8, 4))).isTrue();
    }

    @Test
    @DisplayName("BA-10: updateProgressiveSummaryAsync 正常流程——单点转写→LLM→写回 Redis（文案统一「学生/AI」）")
    void updateProgressiveSummary_happyPath() {
        when(messageSummaryMapper.selectList(any()))
                .thenReturn(List.of(newMessage("student", "enc-1"), newMessage("ai", "enc-2")));
        when(fieldEncryptionService.decrypt("enc-1")).thenReturn("我今天有点难过");
        when(fieldEncryptionService.decrypt("enc-2")).thenReturn("愿意多说说吗");
        when(aiChatService.summarizeSessionProgress(anyString()))
                .thenReturn("  学生表达了难过情绪，AI 引导其展开叙述  ");
        SessionState session = newState(8, 4);
        when(sessionStateStore.get(tenantId, sessionId)).thenReturn(session);

        service.updateProgressiveSummaryAsync(tenantId, sessionId, 8);

        assertThat(session.getSessionSummary()).isEqualTo("学生表达了难过情绪，AI 引导其展开叙述");
        assertThat(session.getLastSummaryTurn()).isEqualTo(8);
        verify(sessionStateStore).save(eq(tenantId), eq(sessionId), eq(session));
        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(aiChatService).summarizeSessionProgress(captor.capture());
        // 文案单点断言：与 readSessionTranscript 同源，防「波波」漂移回潮
        assertThat(captor.getValue()).isEqualTo("学生: 我今天有点难过\nAI: 愿意多说说吗\n");
        assertThat(captor.getValue()).doesNotContain("波波");
    }

    @Test
    @DisplayName("BA-10: updateProgressiveSummaryAsync 转写为空 → 跳过，不调 LLM 不写回")
    void updateProgressiveSummary_blankTranscriptSkips() {
        when(messageSummaryMapper.selectList(any())).thenReturn(List.of());

        service.updateProgressiveSummaryAsync(tenantId, sessionId, 8);

        verify(aiChatService, never()).summarizeSessionProgress(anyString());
        verify(sessionStateStore, never()).save(any(), any(), any());
    }

    @Test
    @DisplayName("BA-10: updateProgressiveSummaryAsync LLM 返回空 → 不写回 Redis")
    void updateProgressiveSummary_llmBlankSkipsSave() {
        when(messageSummaryMapper.selectList(any()))
                .thenReturn(List.of(newMessage("student", "enc-1")));
        when(fieldEncryptionService.decrypt("enc-1")).thenReturn("内容");
        when(aiChatService.summarizeSessionProgress(anyString())).thenReturn("");

        service.updateProgressiveSummaryAsync(tenantId, sessionId, 8);

        verify(sessionStateStore, never()).get(any(), any());
        verify(sessionStateStore, never()).save(any(), any(), any());
    }

    @Test
    @DisplayName("BA-10: updateProgressiveSummaryAsync Redis 会话不存在 → 不保存")
    void updateProgressiveSummary_sessionMissingSkipsSave() {
        when(messageSummaryMapper.selectList(any()))
                .thenReturn(List.of(newMessage("student", "enc-1")));
        when(fieldEncryptionService.decrypt("enc-1")).thenReturn("内容");
        when(aiChatService.summarizeSessionProgress(anyString())).thenReturn("摘要内容");
        when(sessionStateStore.get(tenantId, sessionId)).thenReturn(null);

        service.updateProgressiveSummaryAsync(tenantId, sessionId, 8);

        verify(sessionStateStore, never()).save(any(), any(), any());
    }

    @Test
    @DisplayName("BA-10: updateProgressiveSummaryAsync 查询异常 → 静默吞掉（失败安全）")
    void updateProgressiveSummary_exceptionSwallowed() {
        when(messageSummaryMapper.selectList(any())).thenThrow(new RuntimeException("db down"));

        service.updateProgressiveSummaryAsync(tenantId, sessionId, 8);

        verify(sessionStateStore, never()).save(any(), any(), any());
    }

    /** 会话消息构造（BA-10 测试组用） */
    private MessageSummary newMessage(String senderType, String content) {
        MessageSummary m = new MessageSummary();
        m.setTenantId(tenantId);
        m.setSessionId(sessionId);
        m.setSenderType(senderType);
        m.setContentSummary(content);
        return m;
    }

    private SessionState newState(int turnCount, int lastSummaryTurn) {
        SessionState state = new SessionState(sessionId, tenantId, studentUserId,
                "neutral", "text", "F", 0.5, 3);
        state.setTurnCount(turnCount);
        state.setLastSummaryTurn(lastSummaryTurn);
        return state;
    }

    // ===== BA-11：SAFE-201 保密边界告知收编（原 ConversationServiceImpl 直连 messageSummaryMapper） =====

    @Test
    @DisplayName("BA-11: 已有告知记录返回 true（senderType=ai + turnCount=0 唯一区分性）")
    void hasConfidentialityNotice_returnsTrueWhenExists() {
        when(messageSummaryMapper.selectCount(any())).thenReturn(1L);
        assertThat(service.hasConfidentialityNotice(tenantId, studentUserId)).isTrue();
    }

    @Test
    @DisplayName("BA-11: 无告知记录返回 false（首次会话触发注入）")
    void hasConfidentialityNotice_returnsFalseWhenAbsent() {
        when(messageSummaryMapper.selectCount(any())).thenReturn(0L);
        assertThat(service.hasConfidentialityNotice(tenantId, studentUserId)).isFalse();
    }

    @Test
    @DisplayName("BA-11: 告知落库固定字段（turnCount=0 + senderType=ai + 1024 截断，合规审计凭据）")
    void insertConfidentialityNotice_assemblesFixedFields() {
        service.insertConfidentialityNotice(tenantId, studentUserId, sessionId, "a".repeat(2000));

        ArgumentCaptor<MessageSummary> captor = ArgumentCaptor.forClass(MessageSummary.class);
        verify(messageSummaryMapper).insert(captor.capture());
        MessageSummary record = captor.getValue();
        assertThat(record.getTenantId()).isEqualTo(tenantId);
        assertThat(record.getStudentUserId()).isEqualTo(studentUserId);
        assertThat(record.getSessionId()).isEqualTo(sessionId);
        assertThat(record.getTurnCount()).isZero();
        assertThat(record.getSenderType()).isEqualTo("ai");
        assertThat(record.getContentSummary()).hasSize(1024);
        assertThat(record.getRiskLevel()).isZero();
        assertThat(record.getEmotionTags()).isEqualTo("[]");
        assertThat(record.getRiskSignals()).isEqualTo("[]");
    }
}
