package com.mindsafe.service.conversation;

import com.mindsafe.ai.chat.AiChatService;
import com.mindsafe.ai.prompt.PromptTemplateService;
import com.mindsafe.ai.risk.RiskDetectorService;
import com.mindsafe.ai.safety.PiiDesensitizer;
import com.mindsafe.common.dto.chat.SessionInfo;
import com.mindsafe.common.dto.chat.StreamMessageEvent;
import com.mindsafe.common.dto.risk.RiskDetectionResult;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.domain.mapper.MessageSummaryMapper;
import com.mindsafe.domain.mapper.RiskEventMapper;
import com.mindsafe.domain.mapper.UserMapper;
import com.mindsafe.service.memory.LongTermMemoryService;
import com.mindsafe.service.notification.NotificationService;
import com.mindsafe.service.profile.ProfileExtractorService;
import com.mindsafe.service.profile.StudentProfileService;
import com.mindsafe.service.prompt.PromptVersionService;
import com.mindsafe.service.usage.UsageTimeLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

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
    private RiskDetectorService riskDetectorService;
    private PiiDesensitizer piiDesensitizer;
    private CounselingSessionMapper sessionMapper;
    private MessageSummaryMapper messageSummaryMapper;
    private RiskEventMapper riskEventMapper;
    private NotificationService notificationService;
    private UserMapper userMapper;
    private StudentProfileService profileService;
    private ProfileExtractorService profileExtractorService;
    private UsageTimeLimitService usageTimeLimitService;
    private LongTermMemoryService longTermMemoryService;
    private PromptVersionService promptVersionService;

    private ConversationServiceImpl service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID studentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        aiChatService = mock(AiChatService.class);
        promptTemplateService = mock(PromptTemplateService.class);
        riskDetectorService = mock(RiskDetectorService.class);
        piiDesensitizer = mock(PiiDesensitizer.class);
        sessionMapper = mock(CounselingSessionMapper.class);
        messageSummaryMapper = mock(MessageSummaryMapper.class);
        riskEventMapper = mock(RiskEventMapper.class);
        notificationService = mock(NotificationService.class);
        userMapper = mock(UserMapper.class);
        profileService = mock(StudentProfileService.class);
        profileExtractorService = mock(ProfileExtractorService.class);
        usageTimeLimitService = mock(UsageTimeLimitService.class);
        longTermMemoryService = mock(LongTermMemoryService.class);
        promptVersionService = mock(PromptVersionService.class);

        // AI-005: PromptVersionService 默认返回 classpath 降级结果
        when(promptVersionService.resolve(any(), anyString(), any(), anyMap()))
                .thenReturn(new PromptVersionService.ResolvedPrompt("mock-system-prompt", "SYS_001:v0:classpath", "control"));
        when(promptVersionService.resolveRaw(any(), anyString(), any()))
                .thenReturn(new PromptVersionService.ResolvedPrompt("mock-lang-rules", "LANG_001:v0:classpath", "control"));

        service = new ConversationServiceImpl(aiChatService, promptTemplateService,
                riskDetectorService, piiDesensitizer, sessionMapper, messageSummaryMapper,
                riskEventMapper, notificationService, userMapper, profileService,
                profileExtractorService, usageTimeLimitService, longTermMemoryService, promptVersionService);
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

    // ===== 反射工具：直接设置 SessionState 的 nudge 状态（模拟时间流逝/次数累积） =====

    private Object getSessionState(UUID sessionId) throws Exception {
        Field f = ConversationServiceImpl.class.getDeclaredField("activeSessions");
        f.setAccessible(true);
        Map<?, ?> map = (Map<?, ?>) f.get(service);
        return map.get(sessionId);
    }

    private void forceNudgeState(UUID sessionId, int count, Instant lastNudgeAt) throws Exception {
        Object state = getSessionState(sessionId);
        Field countField = state.getClass().getDeclaredField("nudgeCount");
        countField.setAccessible(true);
        ((AtomicInteger) countField.get(state)).set(count);
        Field lastField = state.getClass().getDeclaredField("lastNudgeAt");
        lastField.setAccessible(true);
        lastField.set(state, lastNudgeAt);
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
    @DisplayName("冷场 nudge 编排（design/28 §三 3.4）")
    class NudgeStream {

        @Test
        @DisplayName("会话不存在 → 空流")
        void noSession_empty() {
            List<StreamMessageEvent> events = service
                    .sendNudgeStream(tenantId, UUID.randomUUID(), 30)
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
                    .sendNudgeStream(tenantId, sessionId, 60)
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
                    .sendNudgeStream(tenantId, sessionId, 20)
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
                    .sendNudgeStream(tenantId, sessionId, 30)
                    .collectList().block();

            assertThat(events).hasSize(3);
            assertThat(events.get(0).type()).isEqualTo("token");
            assertThat(events.get(0).content()).isEqualTo("波波在呢");
            assertThat(events.get(2).type()).isEqualTo("done");

            // 走 chatProactive（不污染记忆），绝不走 chat
            verify(aiChatService).chatProactive(eq(sessionId), eq("happy"), eq("male"), any(), eq("【暖场指令】强度=2"), any(Integer.class));
            verify(aiChatService, never()).chat(any(), any(), any(), any(), any(), any(Integer.class));
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
            service.sendNudgeStream(tenantId, sessionId, 30).collectList().block();
            // 立即第二次 → 间隔不足被拦截
            List<StreamMessageEvent> second = service
                    .sendNudgeStream(tenantId, sessionId, 55)
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
                    .sendNudgeStream(tenantId, sessionId, 50)
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
            assertThat(service.sendNudgeStream(tenantId, sessionId, 50).collectList().block()).isEmpty();

            // 孩子说话（走 sendMessage 全流程）
            when(riskDetectorService.detect(anyString())).thenReturn(RiskDetectionResult.safe());
            when(piiDesensitizer.desensitize(anyString())).thenAnswer(inv -> inv.getArgument(0));
            when(usageTimeLimitService.isExceeded(any(), any())).thenReturn(false);
            when(profileService.buildProfilePrompt(eq(tenantId), eq(studentId), any(Integer.class), any()))
                    .thenReturn(null);
            when(aiChatService.chatWithPrompt(any(), any(), any(), any(), any(), any(Integer.class), anyString()))
                    .thenReturn(Flux.just(StreamMessageEvent.token("你好呀")));

            List<StreamMessageEvent> chatEvents = service
                    .sendMessageStream(tenantId, sessionId, "波波我想和你聊天")
                    .collectList().block();
            assertThat(chatEvents).isNotEmpty();

            // 计数已清零 + 间隔足够 → 暖场恢复
            when(promptTemplateService.render(anyString(), anyMap())).thenReturn("指令");
            when(aiChatService.chatProactive(any(), any(), any(), any(), any(), any(Integer.class)))
                    .thenReturn(Flux.just(StreamMessageEvent.token("在呢")));
            List<StreamMessageEvent> nudgeEvents = service
                    .sendNudgeStream(tenantId, sessionId, 40)
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
                    .sendNudgeStream(tenantId, sessionId, 30)
                    .collectList().block();

            assertThat(events).isEmpty();
        }
    }

    @Nested
    @DisplayName("年级解析与动态降级（PROF-010/015）")
    class GradeComputation {

        @Test
        @DisplayName("parseGradeCode: 支持 G1-G6、纯数字、null/空/非法 → 默认 4")
        void parseGradeCode_variants() {
            assertThat(ConversationServiceImpl.parseGradeCode("G1")).isEqualTo(1);
            assertThat(ConversationServiceImpl.parseGradeCode("G6")).isEqualTo(6);
            assertThat(ConversationServiceImpl.parseGradeCode("3")).isEqualTo(3);
            assertThat(ConversationServiceImpl.parseGradeCode(null)).isEqualTo(4);
            assertThat(ConversationServiceImpl.parseGradeCode("")).isEqualTo(4);
            assertThat(ConversationServiceImpl.parseGradeCode("abc")).isEqualTo(4);
            assertThat(ConversationServiceImpl.parseGradeCode("G9")).isEqualTo(4);
        }

        @Test
        @DisplayName("computeEffectiveGrade: 无画像数据 → 不降级")
        void noProfile_noDowngrade() {
            assertThat(ConversationServiceImpl.computeEffectiveGrade(5, null, false)).isEqualTo(5);
        }

        @Test
        @DisplayName("computeEffectiveGrade: 风险场景 → 不降级")
        void riskBlocked_noDowngrade() {
            assertThat(ConversationServiceImpl.computeEffectiveGrade(5, 0.1, true)).isEqualTo(5);
        }

        @Test
        @DisplayName("computeEffectiveGrade: 极端沉默(<0.15) → 直接降到 1")
        void extremeSilence_gradeOne() {
            assertThat(ConversationServiceImpl.computeEffectiveGrade(5, 0.1, false)).isEqualTo(1);
            assertThat(ConversationServiceImpl.computeEffectiveGrade(3, 0.14, false)).isEqualTo(1);
        }

        @Test
        @DisplayName("computeEffectiveGrade: 低表达(0.15-0.3) + grade>2 → 降 2 年级")
        void lowExpression_downgrade() {
            assertThat(ConversationServiceImpl.computeEffectiveGrade(5, 0.25, false)).isEqualTo(3);
            assertThat(ConversationServiceImpl.computeEffectiveGrade(4, 0.2, false)).isEqualTo(2);
            assertThat(ConversationServiceImpl.computeEffectiveGrade(3, 0.29, false)).isEqualTo(1);
        }

        @Test
        @DisplayName("computeEffectiveGrade: 低表达 + grade≤2 → 不降（已是最低段）")
        void lowExpression_lowGrade_noDowngrade() {
            assertThat(ConversationServiceImpl.computeEffectiveGrade(2, 0.2, false)).isEqualTo(2);
            assertThat(ConversationServiceImpl.computeEffectiveGrade(1, 0.2, false)).isEqualTo(1);
        }

        @Test
        @DisplayName("computeEffectiveGrade: 正常表达(≥0.3) → 不降级")
        void normalExpression_noDowngrade() {
            assertThat(ConversationServiceImpl.computeEffectiveGrade(5, 0.5, false)).isEqualTo(5);
            assertThat(ConversationServiceImpl.computeEffectiveGrade(3, 0.3, false)).isEqualTo(3);
        }
    }
}
