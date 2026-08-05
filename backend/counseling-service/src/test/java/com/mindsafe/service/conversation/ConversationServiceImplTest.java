package com.mindsafe.service.conversation;

import com.mindsafe.ai.ally.AllianceEnhancer;
import com.mindsafe.ai.cbt.CbtStageRouter;
import com.mindsafe.ai.chat.AiChatService;
import com.mindsafe.ai.orchestrator.EmotionStateMachine;
import com.mindsafe.ai.orchestrator.EntryMoodStrategyResolver;
import com.mindsafe.ai.orchestrator.PromptOrchestrationService;
import com.mindsafe.ai.prompt.PromptTemplateService;
import com.mindsafe.ai.safety.ConfidentialityNotice;
import com.mindsafe.ai.safety.CrisisResourceProvider;
import com.mindsafe.ai.safety.CrisisResources;
import com.mindsafe.ai.safety.PiiDesensitizer;
import com.mindsafe.common.dto.chat.SessionInfo;
import com.mindsafe.common.dto.chat.StreamMessageEvent;
import com.mindsafe.common.dto.risk.RiskDetectionResult;
import com.mindsafe.common.enums.RiskLevel;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.MessageSummary;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.domain.mapper.MessageSummaryMapper;
import com.mindsafe.domain.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindsafe.service.knowledge.RagAdvisorService;
import com.mindsafe.service.memory.LongTermMemoryService;
import com.mindsafe.service.profile.ProfileExtractorService;
import com.mindsafe.service.profile.StudentProfileService;
import com.mindsafe.service.prompt.PromptVersionService;
import com.mindsafe.service.quality.ConversationQualityService;
import com.mindsafe.service.usage.UsageTimeLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ConversationServiceImpl 单元测试（design/28：问候昵称 + 冷场 nudge 编排）
 * <p>
 * 覆盖：问候语"哈喽，[昵称]！"、nudge 护栏（escalated/次数/间隔）、决策留白=空流、
 * 暖场走 chatProactive（不污染记忆：绝不走 chat）、孩子说话清零暖场计数。
 */
class ConversationServiceImplTest {

    private AiChatService aiChatService;
    private PromptTemplateService promptTemplateService;
    private ConversationRiskProcessor riskProcessor;
    private PiiDesensitizer piiDesensitizer;
    private CounselingSessionMapper sessionMapper;
    private MessageSummaryMapper messageSummaryMapper;
    private UserMapper userMapper;
    private StudentProfileService profileService;
    private ProfileExtractorService profileExtractorService;
    private UsageTimeLimitService usageTimeLimitService;
    private LongTermMemoryService longTermMemoryService;
    private PromptVersionService promptVersionService;
    private RagAdvisorService ragAdvisorService;
    private ConversationQualityService conversationQualityService;
    private CrisisResourceProvider crisisResourceProvider;
    private AllianceEnhancer allianceEnhancer;
    private CbtStageRouter cbtStageRouter;
    private SessionEndAnalyticsService sessionEndAnalyticsService;
    private RedisSessionStateStore sessionStateStore;
    private ConversationContextAgent contextAgent;
    private SessionSummaryUpdater sessionSummaryUpdater;

    /** 测试用内存模拟 Redis 存储 */
    private final Map<UUID, SessionState> testSessionStore = new HashMap<>();

    private ConversationServiceImpl service;
    private MessageSummaryService messageSummaryService;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID studentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        aiChatService = mock(AiChatService.class);
        promptTemplateService = mock(PromptTemplateService.class);
        riskProcessor = mock(ConversationRiskProcessor.class);
        piiDesensitizer = mock(PiiDesensitizer.class);
        sessionMapper = mock(CounselingSessionMapper.class);
        messageSummaryMapper = mock(MessageSummaryMapper.class);
        userMapper = mock(UserMapper.class);
        profileService = mock(StudentProfileService.class);
        profileExtractorService = mock(ProfileExtractorService.class);
        usageTimeLimitService = mock(UsageTimeLimitService.class);
        longTermMemoryService = mock(LongTermMemoryService.class);
        promptVersionService = mock(PromptVersionService.class);
        ragAdvisorService = mock(RagAdvisorService.class);
        conversationQualityService = mock(ConversationQualityService.class);
        crisisResourceProvider = new CrisisResourceProvider();
        allianceEnhancer = mock(AllianceEnhancer.class);
        // CBT-201/202 + WIRE-002：纯规则无依赖，直接用真实实例（验证真实接线路径）
        cbtStageRouter = new CbtStageRouter();
        sessionEndAnalyticsService = mock(SessionEndAnalyticsService.class);
        sessionStateStore = mock(RedisSessionStateStore.class);
        contextAgent = mock(ConversationContextAgent.class);
        sessionSummaryUpdater = mock(SessionSummaryUpdater.class);
        // CTX-Agent: 默认返回空简报（不阻塞 sendMessageStream 测试）
        when(contextAgent.buildContextBrief(any(), any(), any(), any(), anyInt())).thenReturn("");

        // 用内存 Map 模拟 Redis 存储行为
        org.mockito.Mockito.doAnswer(inv -> {
            testSessionStore.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(sessionStateStore).save(any(UUID.class), any(SessionState.class));
        when(sessionStateStore.get(any(UUID.class))).thenAnswer(inv -> testSessionStore.get(inv.getArgument(0)));
        org.mockito.Mockito.doAnswer(inv -> {
            testSessionStore.remove(inv.getArgument(0));
            return null;
        }).when(sessionStateStore).remove(any(UUID.class));

        // P0-2: ConversationRiskProcessor 默认行为（无风险正常流程）
        when(riskProcessor.detectKeywordRisk(anyString())).thenReturn(RiskDetectionResult.safe());
        when(riskProcessor.applySemanticRisk(any(RiskDetectionResult.class), anyString(), anyInt()))
                .thenAnswer(inv -> inv.getArgument(0));
        when(riskProcessor.fuseRiskSignals(any(RiskDetectionResult.class), any(), any(), anyInt()))
                .thenReturn(null);

        // AI-005: PromptVersionService 默认返回 classpath 降级结果
        when(promptVersionService.resolve(any(), anyString(), any(), anyMap()))
                .thenReturn(new PromptVersionService.ResolvedPrompt("mock-system-prompt", "SYS_001:v0:classpath", "control"));
        when(promptVersionService.resolveRaw(any(), anyString(), any()))
                .thenReturn(new PromptVersionService.ResolvedPrompt("mock-lang-rules", "LANG_001:v0:classpath", "control"));

        // SAFE-201: 默认学生已完成保密告知（selectCount=1），告知注入测试组内单独覆盖为 0
        when(messageSummaryMapper.selectCount(any())).thenReturn(1L);

        // KB-101b: RAG 默认不触发（空串），RAG 注入测试组内单独覆盖
        when(ragAdvisorService.buildRagContext(any(), anyString(), anyInt())).thenReturn("");


        // 构建 MessageSummaryService（使用同一组 mock）
        com.mindsafe.service.security.FieldEncryptionService plainEnc =
                new com.mindsafe.service.security.FieldEncryptionService(
                        "", 1, "", new org.springframework.core.env.StandardEnvironment());
        messageSummaryService = new MessageSummaryService(messageSummaryMapper, sessionMapper,
                aiChatService, plainEnc, conversationQualityService, profileExtractorService, longTermMemoryService,
                new ObjectMapper());

        service = new ConversationServiceImpl(aiChatService, promptTemplateService,
                riskProcessor, piiDesensitizer, sessionMapper, messageSummaryMapper,
                userMapper, profileService,
                usageTimeLimitService, longTermMemoryService, promptVersionService,
                ragAdvisorService,
                // ORCH-001/003：编排引擎+情绪状态机纯规则无依赖，直接用真实实例
                new PromptOrchestrationService(new EntryMoodStrategyResolver(), new EmotionStateMachine()),
                messageSummaryService,
                plainEnc,
                crisisResourceProvider,
                allianceEnhancer, cbtStageRouter,
                sessionEndAnalyticsService, sessionStateStore,
                contextAgent, sessionSummaryUpdater);
    }

    /** createSession 并捕获内部生成的 sessionId */
    private UUID createSession(String emotionTag) {
        return createSession(emotionTag, "小明");
    }

    private UUID createSession(String emotionTag, String pseudonym) {
        User user = new User();
        user.setPseudonym(pseudonym);
        user.setGender("male");
        when(userMapper.selectById(studentId)).thenReturn(user);
        when(profileService.getExpressionDepth(tenantId, studentId)).thenReturn(null);

        SessionInfo info = service.createSession(tenantId, studentId, emotionTag, "web");
        return info.sessionId();
    }

    /** 模拟会话未 escalated */
    private void mockSessionActive(UUID sessionId) {
        CounselingSession entity = new CounselingSession();
        entity.setSessionId(sessionId);
        entity.setSessionStatus("active");
        when(sessionMapper.selectById(sessionId)).thenReturn(entity);
    }

    // ===== 会话状态工具：直接操作 testSessionStore 中的 SessionState =====

    private SessionState getSessionState(UUID sessionId) {
        return testSessionStore.get(sessionId);
    }

    private void forceNudgeState(UUID sessionId, int count, Instant lastNudgeAt) {
        SessionState state = testSessionStore.get(sessionId);
        state.setNudgeCount(count);
        state.setLastNudgeAt(lastNudgeAt);
    }

    @Nested
    @DisplayName("问候语个性化（design/28 §2.2）")
    class Greeting {

        @Test
        @DisplayName("有昵称 → '哈喽，[昵称]！' + 情绪问候")
        void greetingWithPseudonym() {
            User user = new User();
            user.setPseudonym("小明");
            when(userMapper.selectById(studentId)).thenReturn(user);
            when(profileService.getExpressionDepth(tenantId, studentId)).thenReturn(null);

            SessionInfo info = service.createSession(tenantId, studentId, "happy", "web");

            assertThat(info.greeting()).startsWith("哈喽，小明！");
            assertThat(info.greeting()).contains("心情不错");
        }

        @Test
        @DisplayName("各情绪标签均有对应问候")
        void greetingPerEmotion() {
            when(userMapper.selectById(studentId)).thenReturn(null);
            when(profileService.getExpressionDepth(tenantId, studentId)).thenReturn(null);

            assertThat(service.createSession(tenantId, studentId, "sad", "web").greeting())
                    .startsWith("哈喽！").contains("难过");
            assertThat(service.createSession(tenantId, studentId, "scared", "web").greeting())
                    .contains("害怕");
            assertThat(service.createSession(tenantId, studentId, "nervous", "web").greeting())
                    .contains("紧张");
            assertThat(service.createSession(tenantId, studentId, "angry", "web").greeting())
                    .contains("生气");
        }

        @Test
        @DisplayName("用户不存在/昵称为空 → 回退 '哈喽！'")
        void greetingFallbackWithoutPseudonym() {
            when(userMapper.selectById(studentId)).thenReturn(null);
            when(profileService.getExpressionDepth(tenantId, studentId)).thenReturn(null);

            SessionInfo info = service.createSession(tenantId, studentId, "happy", "web");
            assertThat(info.greeting()).startsWith("哈喽！");

            User blankUser = new User();
            blankUser.setPseudonym("  ");
            when(userMapper.selectById(studentId)).thenReturn(blankUser);
            SessionInfo info2 = service.createSession(tenantId, studentId, "happy", "web");
            assertThat(info2.greeting()).startsWith("哈喽！");
        }

        @Test
        @DisplayName("createSession 加载画像沟通偏好（信号 F）")
        void createSessionLoadsExpressionDepth() {
            createSession("happy");
            verify(profileService).getExpressionDepth(tenantId, studentId);
        }
    }

    @Nested
    @DisplayName("SAFE-201 保密边界告知注入（design/14 §12.3）")
    class ConfidentialityNoticeInjection {

        private User studentWithGrade(String gradeCode) {
            User user = new User();
            user.setPseudonym("小明");
            user.setGradeCode(gradeCode);
            when(userMapper.selectById(studentId)).thenReturn(user);
            when(profileService.getExpressionDepth(tenantId, studentId)).thenReturn(null);
            return user;
        }

        @Test
        @DisplayName("首次会话 → 问候语拼接标准版告知 + 落 turn=0 审计记录")
        void firstSession_injectsNotice_andPersistsAuditRecord() {
            when(messageSummaryMapper.selectCount(any())).thenReturn(0L);
            studentWithGrade("G4");

            SessionInfo info = service.createSession(tenantId, studentId, "happy", "web");

            assertThat(info.greeting()).startsWith("哈喽，小明！");
            assertThat(info.greeting()).contains(ConfidentialityNotice.NOTICE_STANDARD);

            ArgumentCaptor<MessageSummary> captor = ArgumentCaptor.forClass(MessageSummary.class);
            verify(messageSummaryMapper).insert(captor.capture());
            MessageSummary record = captor.getValue();
            assertThat(record.getSenderType()).isEqualTo("ai");
            assertThat(record.getTurnCount()).isZero();
            assertThat(record.getContentSummary()).isEqualTo(ConfidentialityNotice.NOTICE_STANDARD);
            assertThat(record.getStudentUserId()).isEqualTo(studentId);
        }

        @Test
        @DisplayName("1-2 年级 → 柔和简化版告知")
        void lowerGrade_usesSimplifiedNotice() {
            when(messageSummaryMapper.selectCount(any())).thenReturn(0L);
            studentWithGrade("G2");

            SessionInfo info = service.createSession(tenantId, studentId, "happy", "web");

            assertThat(info.greeting()).contains(ConfidentialityNotice.NOTICE_LOWER_GRADE);
            assertThat(info.greeting()).doesNotContain(ConfidentialityNotice.NOTICE_STANDARD);
        }

        @Test
        @DisplayName("已告知学生二次会话 → 不重复注入、不重复落库")
        void alreadyNoticed_notRepeated() {
            studentWithGrade("G4");

            SessionInfo info = service.createSession(tenantId, studentId, "happy", "web");

            assertThat(info.greeting()).doesNotContain("三件小事");
            verify(messageSummaryMapper, never()).insert(any(MessageSummary.class));
        }

        @Test
        @DisplayName("forGrade 分年级选版：1-2 简化版 / 3-6 标准版")
        void forGrade_variants() {
            assertThat(ConfidentialityNotice.forGrade(1)).isEqualTo(ConfidentialityNotice.NOTICE_LOWER_GRADE);
            assertThat(ConfidentialityNotice.forGrade(2)).isEqualTo(ConfidentialityNotice.NOTICE_LOWER_GRADE);
            assertThat(ConfidentialityNotice.forGrade(3)).isEqualTo(ConfidentialityNotice.NOTICE_STANDARD);
            assertThat(ConfidentialityNotice.forGrade(6)).isEqualTo(ConfidentialityNotice.NOTICE_STANDARD);
        }
    }

    @Nested
    @DisplayName("R-01 字段级加密接线（contentSummary 落库加密/读取解密）")
    class FieldEncryptionWiring {

        /** 32 字节全零测试密钥（Base64），仅用于验证加解密接线，非生产密钥 */
        private static final String TEST_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

        @Test
        @DisplayName("AI 回复落库前经字段级加密（密文可解密还原）")
        void aiReplyPersistedEncrypted() {
            com.mindsafe.service.security.FieldEncryptionService keyedEnc =
                    new com.mindsafe.service.security.FieldEncryptionService(
                            TEST_KEY, 1, "", new org.springframework.core.env.StandardEnvironment());
            ConversationServiceImpl keyedService = new ConversationServiceImpl(aiChatService, promptTemplateService,
                    riskProcessor, piiDesensitizer, sessionMapper, messageSummaryMapper,
                    userMapper, profileService,
                    usageTimeLimitService, longTermMemoryService, promptVersionService,
                    ragAdvisorService,
                    new PromptOrchestrationService(new EntryMoodStrategyResolver(), new EmotionStateMachine()),
                    new MessageSummaryService(messageSummaryMapper, sessionMapper,
                            aiChatService, keyedEnc, conversationQualityService, profileExtractorService, longTermMemoryService,
                            new ObjectMapper()),
                    keyedEnc,
                    crisisResourceProvider,
                    allianceEnhancer, cbtStageRouter,
                    sessionEndAnalyticsService, sessionStateStore,
                    contextAgent, sessionSummaryUpdater);

            User user = new User();
            user.setPseudonym("小明");
            user.setGender("male");
            when(userMapper.selectById(studentId)).thenReturn(user);
            when(profileService.getExpressionDepth(tenantId, studentId)).thenReturn(null);
            UUID sessionId = keyedService.createSession(tenantId, studentId, "happy", "web").sessionId();

            mockSessionActive(sessionId);
            when(promptTemplateService.render(eq(PromptTemplateService.TSK_004), anyMap()))
                    .thenReturn("【暖场指令】强度=2");
            when(profileService.buildProfilePrompt(eq(tenantId), eq(studentId), any(Integer.class), any()))
                    .thenReturn(null);
            when(aiChatService.chatProactive(eq(sessionId), eq("happy"), eq("male"), any(), eq("【暖场指令】强度=2"), any(Integer.class)))
                    .thenReturn(Flux.just(StreamMessageEvent.token("波波在呢"), StreamMessageEvent.token("～")));

            keyedService.sendNudgeStream(tenantId, studentId, sessionId, 30).collectList().block();

            ArgumentCaptor<MessageSummary> captor = ArgumentCaptor.forClass(MessageSummary.class);
            verify(messageSummaryMapper).insert(captor.capture());
            MessageSummary record = captor.getValue();
            // 落库值为密文（非明文），且可解密还原为原回复
            assertThat(keyedEnc.isEncrypted(record.getContentSummary())).isTrue();
            assertThat(record.getContentSummary()).doesNotContain("波波在呢");
            assertThat(keyedEnc.decrypt(record.getContentSummary())).isEqualTo("波波在呢～");
        }
    }

    @Nested
    @DisplayName("冷场 nudge 编排（design/28 §三 3.4）")
    class NudgeStream {

        @Test
        @DisplayName("会话不存在 → 空流")
        void noSession_empty() {
            List<StreamMessageEvent> events = service
                    .sendNudgeStream(tenantId, studentId, UUID.randomUUID(), 30)
                    .collectList().block();
            assertThat(events).isEmpty();
            verify(aiChatService, never()).chatProactive(any(), any(), any(), any(), any(), anyInt());
        }

        @Test
        @DisplayName("会话已 escalated（红色风险接管）→ 空流，不做日常暖场")
        void escalated_empty() {
            UUID sessionId = createSession("happy");
            CounselingSession entity = new CounselingSession();
            entity.setSessionId(sessionId);
            entity.setSessionStatus("escalated");
            when(sessionMapper.selectById(sessionId)).thenReturn(entity);

            List<StreamMessageEvent> events = service
                    .sendNudgeStream(tenantId, studentId, sessionId, 60)
                    .collectList().block();

            assertThat(events).isEmpty();
            verify(aiChatService, never()).chatProactive(any(), any(), any(), any(), any(), anyInt());
        }

        @Test
        @DisplayName("决策=留白（愤怒+短沉默）→ 空流，把安静还给孩子")
        void decisionSilence_empty() {
            UUID sessionId = createSession("angry");
            mockSessionActive(sessionId);

            // angry(-1) + 20s(B=0) + 前期(D+1) = 0 → 留白
            List<StreamMessageEvent> events = service
                    .sendNudgeStream(tenantId, studentId, sessionId, 20)
                    .collectList().block();

            assertThat(events).isEmpty();
            verify(aiChatService, never()).chatProactive(any(), any(), any(), any(), any(), anyInt());
        }

        @Test
        @DisplayName("决策=暖场 → TSK-004 渲染 + chatProactive + 回复落库 + done 事件")
        void decisionWarm_streamsProactiveReply() {
            UUID sessionId = createSession("happy");
            mockSessionActive(sessionId);
            when(promptTemplateService.render(eq(PromptTemplateService.TSK_004), anyMap()))
                    .thenReturn("【暖场指令】强度=2");
            when(profileService.buildProfilePrompt(eq(tenantId), eq(studentId), any(Integer.class), any()))
                    .thenReturn(null);
            when(aiChatService.chatProactive(eq(sessionId), eq("happy"), eq("male"), any(), eq("【暖场指令】强度=2"), any(Integer.class)))
                    .thenReturn(Flux.just(StreamMessageEvent.token("波波在呢"), StreamMessageEvent.token("～")));

            // happy(+1) + 30s(B+1) + 前期(D+1) = 3 → 引导破冰
            List<StreamMessageEvent> events = service
                    .sendNudgeStream(tenantId, studentId, sessionId, 30)
                    .collectList().block();

            assertThat(events).hasSize(3);
            assertThat(events.get(0).type()).isEqualTo("token");
            assertThat(events.get(0).content()).isEqualTo("波波在呢");
            assertThat(events.get(2).type()).isEqualTo("done");

            // 走 chatProactive（不污染记忆）
            verify(aiChatService).chatProactive(eq(sessionId), eq("happy"), eq("male"), any(), eq("【暖场指令】强度=2"), any(Integer.class));
            // TSK-004 渲染含决策参数
            verify(promptTemplateService).render(eq(PromptTemplateService.TSK_004), anyMap());
            // AI 暖场回复落库（孩子看到的连续性保留）
            verify(messageSummaryMapper).insert(any(com.mindsafe.domain.entity.MessageSummary.class));
        }

        @Test
        @DisplayName("护栏：距上次暖场 <20s → 空流")
        void intervalGuard_empty() {
            UUID sessionId = createSession("happy");
            mockSessionActive(sessionId);
            when(promptTemplateService.render(anyString(), anyMap())).thenReturn("指令");
            when(aiChatService.chatProactive(any(), any(), any(), any(), any(), any(Integer.class)))
                    .thenReturn(Flux.just(StreamMessageEvent.token("在呢")));

            // 第一次暖场成功
            service.sendNudgeStream(tenantId, studentId, sessionId, 30).collectList().block();
            // 立即第二次 → 间隔不足被拦截
            List<StreamMessageEvent> second = service
                    .sendNudgeStream(tenantId, studentId, sessionId, 55)
                    .collectList().block();

            assertThat(second).isEmpty();
            verify(aiChatService, times(1)).chatProactive(any(), any(), any(), any(), any(), any(Integer.class));
        }

        @Test
        @DisplayName("护栏：连续暖场 ≥2 次 → 空流（即使间隔足够）")
        void countGuard_empty() throws Exception {
            UUID sessionId = createSession("happy");
            mockSessionActive(sessionId);
            forceNudgeState(sessionId, 2, Instant.now().minusSeconds(60));

            List<StreamMessageEvent> events = service
                    .sendNudgeStream(tenantId, studentId, sessionId, 50)
                    .collectList().block();

            assertThat(events).isEmpty();
            verify(aiChatService, never()).chatProactive(any(), any(), any(), any(), any(), any(Integer.class));
        }

        @Test
        @DisplayName("孩子一说话即清零暖场计数（重置后可再次暖场）")
        void studentMessageResetsNudgeCount() throws Exception {
            UUID sessionId = createSession("happy");
            mockSessionActive(sessionId);
            forceNudgeState(sessionId, 2, Instant.now().minusSeconds(60));

            // 暖场被次数护栏拦截
            assertThat(service.sendNudgeStream(tenantId, studentId, sessionId, 50).collectList().block()).isEmpty();

            // 孩子说话（走 sendMessage 全流程）
            when(piiDesensitizer.desensitize(anyString())).thenAnswer(inv -> inv.getArgument(0));
            when(usageTimeLimitService.isExceeded(any(), any())).thenReturn(false);
            when(profileService.buildProfilePrompt(eq(tenantId), eq(studentId), any(Integer.class), any()))
                    .thenReturn(null);
            when(aiChatService.chatWithPrompt(any(), any(), any(), any(), any(), anyInt(), anyString()))
                    .thenReturn(Flux.just(StreamMessageEvent.token("你好呀")));

            List<StreamMessageEvent> chatEvents = service
                    .sendMessageStream(tenantId, studentId, sessionId, "波波我想和你聊天")
                    .collectList().block();
            assertThat(chatEvents).isNotEmpty();

            // 计数已清零 + 间隔足够 → 暖场恢复
            when(promptTemplateService.render(anyString(), anyMap())).thenReturn("指令");
            when(aiChatService.chatProactive(any(), any(), any(), any(), any(), any(Integer.class)))
                    .thenReturn(Flux.just(StreamMessageEvent.token("在呢")));
            List<StreamMessageEvent> nudgeEvents = service
                    .sendNudgeStream(tenantId, studentId, sessionId, 40)
                    .collectList().block();

            assertThat(nudgeEvents).isNotEmpty();
            verify(aiChatService, times(1)).chatProactive(any(), any(), any(), any(), any(), any(Integer.class));
        }

        @Test
        @DisplayName("暖场 AI 调用异常 → 静默返回空流（不打扰孩子）")
        void proactiveError_silentEmpty() {
            UUID sessionId = createSession("happy");
            mockSessionActive(sessionId);
            when(promptTemplateService.render(anyString(), anyMap())).thenReturn("指令");
            when(profileService.buildProfilePrompt(eq(tenantId), eq(studentId), any(Integer.class), any()))
                    .thenReturn(null);
            when(aiChatService.chatProactive(any(), any(), any(), any(), any(), any(Integer.class)))
                    .thenReturn(Flux.error(new RuntimeException("LLM 超时")));

            List<StreamMessageEvent> events = service
                    .sendNudgeStream(tenantId, studentId, sessionId, 30)
                    .collectList().block();

            assertThat(events).isEmpty();
        }
    }

    @Nested
    @DisplayName("RED 硬短路（RISK-201，design/04 §18.2）")
    class RedShortCircuit {

        /** 构造 RED 硬规则命中结果 */
        private RiskDetectionResult redResult() {
            return new RiskDetectionResult(RiskLevel.RED, "self_harm",
                    List.of("硬规则关键词"), 90, true, "立即通知教师");
        }

        /** 通用 mock：风险检测 RED + 脱敏透传 + 时长未超限 */
        private void mockPipeline() {
            when(riskProcessor.detectKeywordRisk(anyString())).thenReturn(redResult());
            when(riskProcessor.fuseRiskSignals(any(RiskDetectionResult.class), any(), any(), anyInt()))
                    .thenReturn(RiskLevel.RED);
            when(piiDesensitizer.desensitize(anyString())).thenAnswer(inv -> inv.getArgument(0));
            when(usageTimeLimitService.isExceeded(any(), any())).thenReturn(false);
        }

        @Test
        @DisplayName("RED → 跳过 LLM，返回 risk + 预审核安全文案 + done")
        void red_skipsLlm_returnsSafetyReply() {
            UUID sessionId = createSession("sad");
            mockPipeline();

            List<StreamMessageEvent> events = service
                    .sendMessageStream(tenantId, studentId, sessionId, "我不想活了")
                    .collectList().block();

            // 事件序列：risk → 安全文案 token → done（无 LLM 自由生成）
            assertThat(events).hasSize(3);
            assertThat(events.get(0).type()).isEqualTo("risk");
            assertThat(events.get(1).type()).isEqualTo("token");
            assertThat(events.get(1).content()).isEqualTo(CrisisResources.RED_SAFETY_REPLY);
            assertThat(events.get(2).type()).isEqualTo("done");

            // 硬短路：绝不调用 LLM 自由生成
            verify(aiChatService, never()).chatWithPrompt(any(), any(), any(), any(), any(), anyInt(), anyString());
        }

        @Test
        @DisplayName("RED → 教师告警照发 + 会话升级 escalated")
        void red_notifiesTeacher_andEscalates() {
            UUID sessionId = createSession("sad");
            mockPipeline();

            service.sendMessageStream(tenantId, studentId, sessionId, "我不想活了").collectList().block();

            // 风险事件持久化 + 教师通知（委托 riskProcessor）
            verify(riskProcessor).persistRiskEvent(any(SessionState.class), any(RiskDetectionResult.class));

            // 会话状态升级 escalated
            ArgumentCaptor<CounselingSession> captor = ArgumentCaptor.forClass(CounselingSession.class);
            verify(sessionMapper, times(2)).updateById(captor.capture());
            assertThat(captor.getAllValues())
                    .anyMatch(s -> "escalated".equals(s.getSessionStatus()));
        }

        @Test
        @DisplayName("1-2 年级 → 短句版安全文案")
        void red_lowerGrade_usesShortReply() {
            User user = new User();
            user.setPseudonym("小花");
            user.setGradeCode("G2");
            when(userMapper.selectById(studentId)).thenReturn(user);
            when(profileService.getExpressionDepth(tenantId, studentId)).thenReturn(null);
            UUID sessionId = service.createSession(tenantId, studentId, "sad", "web").sessionId();

            mockPipeline();

            List<StreamMessageEvent> events = service
                    .sendMessageStream(tenantId, studentId, sessionId, "我不想活了")
                    .collectList().block();

            assertThat(events.get(1).content()).isEqualTo(CrisisResources.RED_SAFETY_REPLY_LOWER_GRADE);
        }

        @Test
        @DisplayName("安全响应模式：RED 后的后续轮次也不自由生成，返回陪伴话术")
        void safetyMode_subsequentTurns_noLlm() {
            UUID sessionId = createSession("sad");
            mockPipeline();
            service.sendMessageStream(tenantId, studentId, sessionId, "我不想活了").collectList().block();

            // 后续普通消息（无风险，setUp 默认 detectKeywordRisk 返回 safe）
            when(riskProcessor.fuseRiskSignals(any(RiskDetectionResult.class), any(), any(), anyInt()))
                    .thenReturn(null);
            List<StreamMessageEvent> events = service
                    .sendMessageStream(tenantId, studentId, sessionId, "嗯")
                    .collectList().block();

            assertThat(events).hasSize(2);
            assertThat(events.get(0).type()).isEqualTo("token");
            assertThat(events.get(0).content()).isEqualTo(CrisisResources.SAFETY_MODE_COMPANION_REPLY);
            assertThat(events.get(1).type()).isEqualTo("done");
            verify(aiChatService, never()).chatWithPrompt(any(), any(), any(), any(), any(), anyInt(), anyString());
        }

        @Test
        @DisplayName("ORANGE → 不短路，仍走 LLM（附风险事件）")
        void orange_notShortCircuited() {
            UUID sessionId = createSession("sad");
            RiskDetectionResult orangeResult = new RiskDetectionResult(
                    RiskLevel.ORANGE, "bullying", List.of("被打"), 60, false, "建议关注");
            when(riskProcessor.detectKeywordRisk(anyString())).thenReturn(orangeResult);
            when(riskProcessor.fuseRiskSignals(any(RiskDetectionResult.class), any(), any(), anyInt()))
                    .thenReturn(RiskLevel.ORANGE);
            when(piiDesensitizer.desensitize(anyString())).thenAnswer(inv -> inv.getArgument(0));
            when(usageTimeLimitService.isExceeded(any(), any())).thenReturn(false);
            when(profileService.buildProfilePrompt(eq(tenantId), eq(studentId), any(Integer.class), any()))
                    .thenReturn(null);
            when(aiChatService.chatWithPrompt(any(), any(), any(), any(), any(), anyInt(), anyString()))
                    .thenReturn(Flux.just(StreamMessageEvent.token("我在听")));

            List<StreamMessageEvent> events = service
                    .sendMessageStream(tenantId, studentId, sessionId, "我在学校被打了")
                    .collectList().block();

            verify(aiChatService).chatWithPrompt(any(), any(), any(), any(), any(), anyInt(), anyString());
            assertThat(events).anyMatch(e -> "risk".equals(e.type()));
            assertThat(events).anyMatch(e -> "token".equals(e.type()) && "我在听".equals(e.content()));
        }

        @Test
        @DisplayName("redSafetyReply: 分年级选版（1-2 短句版 / 3-6 标准版）")
        void redSafetyReply_gradeVariants() {
            assertThat(ConversationUtils.redSafetyReply(1)).isEqualTo(CrisisResources.RED_SAFETY_REPLY_LOWER_GRADE);
            assertThat(ConversationUtils.redSafetyReply(2)).isEqualTo(CrisisResources.RED_SAFETY_REPLY_LOWER_GRADE);
            assertThat(ConversationUtils.redSafetyReply(3)).isEqualTo(CrisisResources.RED_SAFETY_REPLY);
            assertThat(ConversationUtils.redSafetyReply(6)).isEqualTo(CrisisResources.RED_SAFETY_REPLY);
        }
    }

    @Nested
    @DisplayName("语义风险升级（RISK-202，design/04 §18.3）")
    class SemanticRiskUpgrade {

        /** 通用 mock：脱敏透传 + 时长未超限（detectKeywordRisk 默认 safe 由 setUp 提供） */
        private void mockPipeline() {
            when(piiDesensitizer.desensitize(anyString())).thenAnswer(inv -> inv.getArgument(0));
            when(usageTimeLimitService.isExceeded(any(), any())).thenReturn(false);
        }

        /** LLM 正常回复 mock（未短路场景用） */
        private void mockLlmReply() {
            when(profileService.buildProfilePrompt(eq(tenantId), eq(studentId), any(Integer.class), any()))
                    .thenReturn(null);
            when(aiChatService.chatWithPrompt(any(), any(), any(), any(), any(), anyInt(), anyString()))
                    .thenReturn(Flux.just(StreamMessageEvent.token("我在听")));
        }

        @Test
        @DisplayName("硬规则 GREEN + 语义 RED → 联动 RED 硬短路 + 教师告警")
        void semanticRed_triggersShortCircuit() {
            UUID sessionId = createSession("sad");
            mockPipeline();
            RiskDetectionResult semanticRedResult = new RiskDetectionResult(
                    RiskLevel.RED, "llm_semantic", List.of(), 85, false, "语义分析识别到隐性风险表达");
            when(riskProcessor.applySemanticRisk(any(RiskDetectionResult.class), anyString(), anyInt()))
                    .thenReturn(semanticRedResult);
            when(riskProcessor.fuseRiskSignals(any(RiskDetectionResult.class), any(), any(), anyInt()))
                    .thenReturn(RiskLevel.RED);

            List<StreamMessageEvent> events = service
                    .sendMessageStream(tenantId, studentId, sessionId, "如果我消失就好了")
                    .collectList().block();

            // 语义升级后走 RISK-201 同一条硬短路链路：risk → 安全文案 → done
            assertThat(events).hasSize(3);
            assertThat(events.get(0).type()).isEqualTo("risk");
            assertThat(events.get(1).content()).isEqualTo(CrisisResources.RED_SAFETY_REPLY);
            assertThat(events.get(2).type()).isEqualTo("done");
            verify(aiChatService, never()).chatWithPrompt(any(), any(), any(), any(), any(), anyInt(), anyString());
            verify(riskProcessor).persistRiskEvent(any(SessionState.class), any(RiskDetectionResult.class));
        }

        @Test
        @DisplayName("语义分类失败（null）→ 降级纯硬规则，正常 LLM 流程")
        void semanticNull_fallsBackToNormalFlow() {
            UUID sessionId = createSession("sad");
            mockPipeline();
            mockLlmReply();
            // setUp 默认 applySemanticRisk 透传输入（safe），fuseRiskSignals 返回 null

            List<StreamMessageEvent> events = service
                    .sendMessageStream(tenantId, studentId, sessionId, "今天有点累")
                    .collectList().block();

            verify(riskProcessor).applySemanticRisk(any(RiskDetectionResult.class), anyString(), anyInt());
            verify(aiChatService).chatWithPrompt(any(), any(), any(), any(), any(), anyInt(), anyString());
            assertThat(events).noneMatch(e -> "risk".equals(e.type()));
        }

        @Test
        @DisplayName("硬规则已 RED → 服务层仍调 applySemanticRisk（内部跳过逻辑在 processor 内）")
        void keywordRed_stillCallsProcessor() {
            UUID sessionId = createSession("sad");
            RiskDetectionResult redResult = new RiskDetectionResult(
                    RiskLevel.RED, "self_harm", List.of("硬规则关键词"), 90, true, "立即通知教师");
            when(riskProcessor.detectKeywordRisk(anyString())).thenReturn(redResult);
            when(riskProcessor.fuseRiskSignals(any(RiskDetectionResult.class), any(), any(), anyInt()))
                    .thenReturn(RiskLevel.RED);
            when(piiDesensitizer.desensitize(anyString())).thenAnswer(inv -> inv.getArgument(0));
            when(usageTimeLimitService.isExceeded(any(), any())).thenReturn(false);

            service.sendMessageStream(tenantId, studentId, sessionId, "我不想活了").collectList().block();

            verify(riskProcessor).applySemanticRisk(eq(redResult), anyString(), anyInt());
        }

        @Test
        @DisplayName("硬规则 GREEN + 语义 YELLOW → 发 risk 事件但不短路")
        void semanticYellow_riskEventNoShortCircuit() {
            UUID sessionId = createSession("sad");
            mockPipeline();
            mockLlmReply();
            RiskDetectionResult yellowResult = new RiskDetectionResult(
                    RiskLevel.YELLOW, "llm_semantic", List.of(), 40, false, "语义分析识别到低风险表达");
            when(riskProcessor.applySemanticRisk(any(RiskDetectionResult.class), anyString(), anyInt()))
                    .thenReturn(yellowResult);
            when(riskProcessor.fuseRiskSignals(any(RiskDetectionResult.class), any(), any(), anyInt()))
                    .thenReturn(RiskLevel.YELLOW);

            List<StreamMessageEvent> events = service
                    .sendMessageStream(tenantId, studentId, sessionId, "想睡一辈子不醒")
                    .collectList().block();

            assertThat(events).anyMatch(e -> "risk".equals(e.type()));
            verify(aiChatService).chatWithPrompt(any(), any(), any(), any(), any(), anyInt(), anyString());
            verify(riskProcessor).persistRiskEvent(any(SessionState.class), any(RiskDetectionResult.class));
        }

        @Test
        @DisplayName("语义分类只收脱敏文（原始 PII 不进 LLM）")
        void classifierReceivesDesensitizedText() {
            UUID sessionId = createSession("sad");
            when(piiDesensitizer.desensitize(anyString())).thenReturn("我住在[地址]，很难过");
            when(usageTimeLimitService.isExceeded(any(), any())).thenReturn(false);
            mockLlmReply();

            service.sendMessageStream(tenantId, studentId, sessionId, "我住在幸福路1号，很难过").collectList().block();

            verify(riskProcessor).applySemanticRisk(any(RiskDetectionResult.class), eq("我住在[地址]，很难过"), anyInt());
        }
    }

    @Nested
    @DisplayName("RAG 参考知识注入（KB-101b，design/49 §六）")
    class RagInjection {

        private void mockChatPipeline() {
            when(piiDesensitizer.desensitize(anyString())).thenAnswer(inv -> inv.getArgument(0));
            when(usageTimeLimitService.isExceeded(any(), any())).thenReturn(false);
            when(profileService.buildProfilePrompt(eq(tenantId), eq(studentId), any(Integer.class), any()))
                    .thenReturn(null);
            when(aiChatService.chatWithPrompt(any(), any(), any(), any(), any(), anyInt(), anyString()))
                    .thenReturn(Flux.just(StreamMessageEvent.token("我在听")));
        }

        @Test
        @DisplayName("触发检索 → RAG 上下文拼入 System Prompt 尾部（不覆盖安全规则）")
        void ragContext_appendedToSystemPrompt() {
            UUID sessionId = createSession("sad");
            mockChatPipeline();
            String ragContext = "# 参考资料（心理辅导知识库检索，仅供辅助参考）\n[1] (cbt_technique) KB-001";
            when(ragAdvisorService.buildRagContext(eq(tenantId), anyString(), anyInt()))
                    .thenReturn(ragContext);

            service.sendMessageStream(tenantId, studentId, sessionId, "我考试考砸了很难过").collectList().block();

            ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
            verify(aiChatService).chatWithPrompt(any(), any(), any(), any(), any(), anyInt(), promptCaptor.capture());
            // Fix 3: RAG 拼在系统 Prompt 之后，ContextBrief 追加在最尾部（recency bias）
            assertThat(promptCaptor.getValue())
                    .startsWith("mock-system-prompt")
                    .contains(ragContext);
        }

        @Test
        @DisplayName("未触发（闲聊/无命中）→ System Prompt 不含参考资料")
        void noRagContext_promptUnchanged() {
            UUID sessionId = createSession("happy");
            mockChatPipeline();
            // 默认 stub 已返回空串（未触发）

            service.sendMessageStream(tenantId, studentId, sessionId, "波波你在吗").collectList().block();

            ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
            verify(aiChatService).chatWithPrompt(any(), any(), any(), any(), any(), anyInt(), promptCaptor.capture());
            // ORCH-002：组装链 SYS → LANG → EMO + Fix 3: ContextBrief 追加在尾部
            assertThat(promptCaptor.getValue())
                    .startsWith("mock-system-prompt\n\nmock-lang-rules\n\nmock-system-prompt")
                    .doesNotContain("参考资料");
        }

        @Test
        @DisplayName("RED 硬短路 → 不调用 RAG 检索（危机场景固定话术优先）")
        void redShortCircuit_noRagRetrieval() {
            UUID sessionId = createSession("sad");
            when(riskProcessor.detectKeywordRisk(anyString())).thenReturn(new RiskDetectionResult(
                    RiskLevel.RED, "self_harm", List.of("硬规则关键词"), 90, true, "立即通知教师"));
            when(riskProcessor.fuseRiskSignals(any(RiskDetectionResult.class), any(), any(), anyInt()))
                    .thenReturn(RiskLevel.RED);
            when(piiDesensitizer.desensitize(anyString())).thenAnswer(inv -> inv.getArgument(0));

            service.sendMessageStream(tenantId, studentId, sessionId, "我不想活了").collectList().block();

            verify(ragAdvisorService, never()).buildRagContext(any(), anyString(), anyInt());
        }
    }

    @Nested
    @DisplayName("年级解析与动态降级（PROF-010/015）")
    class GradeComputation {

        @Test
        @DisplayName("parseGradeCode: 支持 G1-G6、纯数字、null/空/非法 → 默认 4")
        void parseGradeCode_variants() {
            assertThat(ConversationUtils.parseGradeCode("G1")).isEqualTo(1);
            assertThat(ConversationUtils.parseGradeCode("G6")).isEqualTo(6);
            assertThat(ConversationUtils.parseGradeCode("3")).isEqualTo(3);
            assertThat(ConversationUtils.parseGradeCode(null)).isEqualTo(4);
            assertThat(ConversationUtils.parseGradeCode("")).isEqualTo(4);
            assertThat(ConversationUtils.parseGradeCode("abc")).isEqualTo(4);
            assertThat(ConversationUtils.parseGradeCode("G9")).isEqualTo(4);
        }

        @Test
        @DisplayName("computeEffectiveGrade: 无画像数据 → 不降级")
        void noProfile_noDowngrade() {
            assertThat(ConversationUtils.computeEffectiveGrade(5, null, false)).isEqualTo(5);
        }

        @Test
        @DisplayName("computeEffectiveGrade: 风险场景 → 不降级")
        void riskBlocked_noDowngrade() {
            assertThat(ConversationUtils.computeEffectiveGrade(5, 0.1, true)).isEqualTo(5);
        }

        @Test
        @DisplayName("computeEffectiveGrade: 极端沉默(<0.15) → 直接降到 1")
        void extremeSilence_gradeOne() {
            assertThat(ConversationUtils.computeEffectiveGrade(5, 0.1, false)).isEqualTo(1);
            assertThat(ConversationUtils.computeEffectiveGrade(3, 0.14, false)).isEqualTo(1);
        }

        @Test
        @DisplayName("computeEffectiveGrade: 低表达(0.15-0.3) + grade>2 → 降 2 年级")
        void lowExpression_downgrade() {
            assertThat(ConversationUtils.computeEffectiveGrade(5, 0.25, false)).isEqualTo(3);
            assertThat(ConversationUtils.computeEffectiveGrade(4, 0.2, false)).isEqualTo(2);
            assertThat(ConversationUtils.computeEffectiveGrade(3, 0.29, false)).isEqualTo(1);
        }

        @Test
        @DisplayName("computeEffectiveGrade: 低表达 + grade≤2 → 不降（已是最低段）")
        void lowExpression_lowGrade_noDowngrade() {
            assertThat(ConversationUtils.computeEffectiveGrade(2, 0.2, false)).isEqualTo(2);
            assertThat(ConversationUtils.computeEffectiveGrade(1, 0.2, false)).isEqualTo(1);
        }

        @Test
        @DisplayName("computeEffectiveGrade: 正常表达(≥0.3) → 不降级")
        void normalExpression_noDowngrade() {
            assertThat(ConversationUtils.computeEffectiveGrade(5, 0.5, false)).isEqualTo(5);
            assertThat(ConversationUtils.computeEffectiveGrade(3, 0.3, false)).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("语音情绪驱动编排（VCL-001，design/47 §4.1/§5.1）")
    class VoiceEmotionOrchestration {

        private void mockChatPipeline() {
            when(piiDesensitizer.desensitize(anyString())).thenAnswer(inv -> inv.getArgument(0));
            when(usageTimeLimitService.isExceeded(any(), any())).thenReturn(false);
            when(profileService.buildProfilePrompt(eq(tenantId), eq(studentId), any(Integer.class), any()))
                    .thenReturn(null);
            when(aiChatService.chatWithPrompt(any(), any(), any(), any(), any(), anyInt(), anyString()))
                    .thenReturn(Flux.just(StreamMessageEvent.token("我在听")));
        }

        /** 捕获 EMO_001 模板变量（编排策略经 toTemplateVariables 渲染的入参） */
        private java.util.Map<String, String> captureEmoVariables() {
            @SuppressWarnings("unchecked")
            ArgumentCaptor<java.util.Map<String, String>> captor = ArgumentCaptor.forClass(java.util.Map.class);
            verify(promptVersionService).resolve(eq(tenantId), eq("EMO_001"), eq(studentId), captor.capture());
            return captor.getValue();
        }

        @Test
        @DisplayName("语音 sad 高置信 → 轮级 currentEmotion 覆盖会话 entryMood，触发 sad 共情策略")
        void voiceSad_overridesEntryMood() {
            UUID sessionId = createSession("happy");
            mockChatPipeline();

            service.sendMessageStream(tenantId, studentId, sessionId, "嗯", "sad", 0.9).collectList().block();

            assertThat(captureEmoVariables().get("entry_mood")).isEqualTo("sad");
        }

        @Test
        @DisplayName("语音置信不足（≤0.6）→ 不驱动策略，回退会话 entryMood")
        void lowConfidence_fallbackToEntryMood() {
            UUID sessionId = createSession("happy");
            mockChatPipeline();

            service.sendMessageStream(tenantId, studentId, sessionId, "嗯", "sad", 0.5).collectList().block();

            assertThat(captureEmoVariables().get("entry_mood")).isEqualTo("happy");
        }

        @Test
        @DisplayName("不可映射标签（unknown/surprised）→ 回退会话 entryMood，不错当平静")
        void unmappableLabel_fallbackToEntryMood() {
            UUID sessionId = createSession("sad");
            mockChatPipeline();

            service.sendMessageStream(tenantId, studentId, sessionId, "嗯", "unknown", 0.9).collectList().block();

            assertThat(captureEmoVariables().get("entry_mood")).isEqualTo("sad");
        }

        @Test
        @DisplayName("TTSFX-004：SSE 事件序列 emotion 先于 token（波波表情需在语音开播前切换，design/37 M1）")
        void emotionEvent_emittedBeforeTokens() {
            UUID sessionId = createSession("happy");
            mockChatPipeline();

            java.util.List<StreamMessageEvent> events = service
                    .sendMessageStream(tenantId, studentId, sessionId, "你好呀")
                    .collectList().block();

            assertThat(events).isNotNull();
            int emotionIdx = -1;
            int tokenIdx = -1;
            for (int i = 0; i < events.size(); i++) {
                if ("emotion".equals(events.get(i).type()) && emotionIdx < 0) emotionIdx = i;
                if ("token".equals(events.get(i).type()) && tokenIdx < 0) tokenIdx = i;
            }
            assertThat(emotionIdx).isGreaterThanOrEqualTo(0);
            assertThat(tokenIdx).isGreaterThan(emotionIdx);
            // happy 入场 + STABLE + 正常推进 → happy（一起放大积极体验）
            assertThat(events.get(emotionIdx).content()).isEqualTo("happy");
        }

        @Test
        @DisplayName("TTSFX-004：情绪激活会话 → 回复情绪 soothe（安抚基调）")
        void activatedSession_emitsSoothe() {
            UUID sessionId = createSession("sad");
            mockChatPipeline();

            java.util.List<StreamMessageEvent> events = service
                    .sendMessageStream(tenantId, studentId, sessionId, "我不想说话")
                    .collectList().block();

            assertThat(events).isNotNull();
            StreamMessageEvent emotionEvent = events.stream()
                    .filter(e -> "emotion".equals(e.type()))
                    .findFirst()
                    .orElseThrow();
            assertThat(emotionEvent.content()).isEqualTo("soothe");
        }

        @Test
        @DisplayName("会话结束 → 语音情绪映射规范集后聚合回注画像（不可映射标签过滤）")
        void endSession_backfillsMappedVoiceEmotions() {
            UUID sessionId = createSession("happy");
            mockChatPipeline();
            service.sendMessageStream(tenantId, studentId, sessionId, "嗯", "sad", 0.9).collectList().block();
            service.sendMessageStream(tenantId, studentId, sessionId, "嗯", "neutral", 0.8).collectList().block();
            service.sendMessageStream(tenantId, studentId, sessionId, "嗯", "unknown", 0.9).collectList().block();

            service.endSession(tenantId, studentId, sessionId);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
            verify(profileService).updateProfile(eq(tenantId), eq(studentId), captor.capture());
            // sad→sad、neutral→calm；unknown 不可映射被过滤
            assertThat(captor.getValue()).containsExactly("sad", "calm");
        }

        @Test
        @DisplayName("纯文本会话结束 → 回注空列表（保留既有语音基线由画像层保证）")
        void textOnlySession_backfillsEmptyList() {
            UUID sessionId = createSession("happy");

            service.endSession(tenantId, studentId, sessionId);

            verify(profileService).updateProfile(eq(tenantId), eq(studentId), eq(List.of()));
        }

        @Test
        @DisplayName("AUDIT-P2-20：endSession 分析收到 DB 真实学生消息（不再传占位空列表）")
        void endSession_passesRealStudentMessagesToAnalyze() {
            UUID sessionId = createSession("happy");

            // 模拟 DB 已落库 2 条学生消息摘要（明文透传加密模式：密文=明文）
            MessageSummary m1 = new MessageSummary();
            m1.setSessionId(sessionId);
            m1.setTenantId(tenantId);
            m1.setSenderType("student");
            m1.setContentSummary("我今天有点不开心");
            MessageSummary m2 = new MessageSummary();
            m2.setSessionId(sessionId);
            m2.setTenantId(tenantId);
            m2.setSenderType("student");
            m2.setContentSummary("嗯");
            when(messageSummaryMapper.selectList(any())).thenReturn(List.of(m1, m2));

            service.endSession(tenantId, studentId, sessionId);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
            verify(sessionEndAnalyticsService)
                    .analyze(eq(tenantId), eq(studentId), any(), any(), captor.capture(), any());
            // 单字过滤由 measureDepth 内部完成，loadStudentMessages 原样透传所有学生消息
            assertThat(captor.getValue()).containsExactly("我今天有点不开心", "嗯");
        }

        @Test
        @DisplayName("AUDIT-P2-20：消息加载失败时降级为空列表（不影响会话结束）")
        void endSession_messageLoadFailureDegradesGracefully() {
            UUID sessionId = createSession("happy");
            // selectList 抛异常（mock 默认 null 也会被 catch 拦截）
            when(messageSummaryMapper.selectList(any()))
                    .thenThrow(new RuntimeException("db down"));

            service.endSession(tenantId, studentId, sessionId);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
            verify(sessionEndAnalyticsService)
                    .analyze(eq(tenantId), eq(studentId), any(), any(), captor.capture(), any());
            assertThat(captor.getValue()).isEmpty();
        }

        @Test
        @DisplayName("endSession 带 @Transactional（fix-tx：先落库再删缓存的原子性保障）")
        void endSession_isTransactional() throws NoSuchMethodException {
            var method = com.mindsafe.service.conversation.ConversationServiceImpl.class
                    .getMethod("endSession", UUID.class, UUID.class, UUID.class);
            org.junit.jupiter.api.Assertions.assertNotNull(
                    method.getAnnotation(org.springframework.transaction.annotation.Transactional.class),
                    "endSession 必须声明 @Transactional");
        }
    }

    @Nested
    @DisplayName("会话质量评估接线（PEVAL-001）")
    class QualityEvaluationWiring {

        @Test
        @DisplayName("摘要流程触发质量评估：对话文本传给 evaluateSessionAsync")
        void summaryFlow_triggersQualityEvaluation() {
            UUID sessionId = UUID.randomUUID();
            when(messageSummaryMapper.selectList(any())).thenReturn(List.of(
                    MessageSummary.studentMessage(tenantId, sessionId, studentId, 1, "我今天很难过", "sad", 0),
                    MessageSummary.aiMessage(tenantId, sessionId, studentId, 1, "我在听")));
            when(aiChatService.generateSessionSummary(anyString())).thenReturn("会话摘要");

            messageSummaryService.generateSummaryAsync(tenantId, sessionId, studentId);

            ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
            verify(conversationQualityService).evaluateSessionAsync(eq(tenantId), eq(sessionId), textCaptor.capture());
            assertThat(textCaptor.getValue()).contains("学生: 我今天很难过").contains("AI: 我在听");
        }

        @Test
        @DisplayName("无消息 → 不触发质量评估")
        void noMessages_noEvaluation() {
            when(messageSummaryMapper.selectList(any())).thenReturn(List.of());

            messageSummaryService.generateSummaryAsync(tenantId, UUID.randomUUID(), studentId);

            verify(conversationQualityService, never()).evaluateSessionAsync(any(), any(), anyString());
        }
    }
}
