package com.mindsafe.ai.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindsafe.ai.prompt.PromptTemplateService;
import com.mindsafe.ai.safety.OutputContentFilter;
import com.mindsafe.ai.safety.OutputReviewService;
import com.mindsafe.ai.safety.OutputSafetyReporter;
import com.mindsafe.ai.safety.SafetyKeywordLibrary;
import com.mindsafe.common.dto.chat.StreamMessageEvent;
import com.mindsafe.domain.entity.ModelCallLog;
import com.mindsafe.domain.mapper.ModelCallLogMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AiChatServiceImpl 单元测试（13/20 篇审计补齐：chatWithPrompt + LLM 调用型方法 + 审计日志）
 * 覆盖：chatWithPrompt 成功/失败路径、clearMemory、摘要/合并提炼(画像+关键事件)/评估/进展摘要的
 * 空输入短路、成功返回、异常降级 null、ModelCallLog 错误消息截断
 */
class AiChatServiceImplLlmCallTest {

    private ChatMemory chatMemory;
    private OutputReviewService outputReviewService;
    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.StreamResponseSpec streamSpec;
    private ChatClient.CallResponseSpec callSpec;
    private ModelCallLogMapper modelCallLogMapper;

    private AiChatServiceImpl service;

    private final UUID sessionId = UUID.randomUUID();
    private final String conversationId = sessionId.toString();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        chatMemory = mock(ChatMemory.class);
        outputReviewService = mock(OutputReviewService.class);
        modelCallLogMapper = mock(ModelCallLogMapper.class);

        SafetyKeywordLibrary library = new SafetyKeywordLibrary(new ObjectMapper());
        OutputContentFilter outputContentFilter =
                new OutputContentFilter(library, mock(OutputSafetyReporter.class));

        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        streamSpec = mock(ChatClient.StreamResponseSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.messages(anyList())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(requestSpec.call()).thenReturn(callSpec);

        service = new AiChatServiceImpl(builder, chatMemory, outputContentFilter,
                outputReviewService,
                new LlmStreamEnhancer(3000, 60000, 1, 10, new SimpleMeterRegistry(), new PromptTemplateService()),
                modelCallLogMapper, new SimpleMeterRegistry());
    }

    // ===== chatWithPrompt（AI-005：预解析 prompt 入口） =====

    @Nested
    @DisplayName("chatWithPrompt")
    class ChatWithPromptTests {

        @Test
        @DisplayName("成功路径：system 原样透传预解析 prompt；记忆双写；审计 success")
        @SuppressWarnings("unchecked")
        void successPath() {
            when(chatMemory.get(conversationId)).thenReturn(List.of());
            when(streamSpec.content()).thenReturn(Flux.just("你好呀"));

            // ARCH-004：profilePrompt 僵尸参数已删除（生产恒传 null）；B4：gender/grade 随性别风格上移调用方
            service.chatWithPrompt(sessionId, "happy", "你好",
                            "预解析的SYS_001+语言模板+性别风格")
                    .collectList().block();

            ArgumentCaptor<String> sysCaptor = ArgumentCaptor.forClass(String.class);
            verify(requestSpec).system(sysCaptor.capture());
            assertThat(sysCaptor.getValue())
                    .contains("预解析的SYS_001+语言模板+性别风格");

            ArgumentCaptor<List<Message>> memCaptor = ArgumentCaptor.forClass(List.class);
            verify(chatMemory, times(2)).add(eq(conversationId), memCaptor.capture());
            assertThat(memCaptor.getAllValues().get(0).get(0)).isInstanceOf(UserMessage.class);
            assertThat(memCaptor.getAllValues().get(1).get(0)).isInstanceOf(AssistantMessage.class);

            verify(outputReviewService).reviewAsync(eq(sessionId), eq("你好呀"), eq("happy"));

            ArgumentCaptor<ModelCallLog> logCaptor = ArgumentCaptor.forClass(ModelCallLog.class);
            verify(modelCallLogMapper).insert(logCaptor.capture());
            assertThat(logCaptor.getValue().getStatus()).isEqualTo("success");
            assertThat(logCaptor.getValue().getAgentName()).isEqualTo("chat");
        }

        @Test
        @DisplayName("画像为空不追加；LLM 失败 → enhancer 降级话术兜底（下游不感知异常）")
        void errorPathFallsBack() {
            when(chatMemory.get(conversationId)).thenReturn(List.of());
            when(streamSpec.content()).thenReturn(Flux.error(new RuntimeException("LLM down")));

            List<StreamMessageEvent> events = service
                    .chatWithPrompt(sessionId, "sad", "难过", "预解析prompt")
                    .collectList().block();

            // 降级话术兜底（重试 1 次后耗尽），下游正常完成不抛异常
            assertThat(events).isNotEmpty();
            assertThat(events.get(0).content()).contains("深呼吸");

            // OPS-P2-01（doing/96，BACK-101）：降级话术属展示产物不进数据面（Q-004 承诺）——
            // 全降级（无真实 token）时不写记忆、不触发 Layer2 审查（原断言"照常审查"为旧行为快照，已修正）
            verify(outputReviewService, times(0)).reviewAsync(any(), any(), any());
            verify(chatMemory, times(1)).add(eq(conversationId), anyList()); // 仅前置用户消息
        }
    }

    @Test
    @DisplayName("clearMemory 委托 ChatMemory.clear")
    void clearMemory() {
        service.clearMemory(sessionId);
        verify(chatMemory).clear(conversationId);
    }

    // ===== LLM 调用型方法（统一模式：空输入短路 / 成功 / 异常降级） =====

    @Nested
    @DisplayName("LLM 调用型方法统一模式")
    class LlmCallMethods {

        @Test
        @DisplayName("generateSessionSummary：空输入短路 / 成功 / 异常返回 null")
        void generateSessionSummary() {
            assertThat(service.generateSessionSummary(null)).isNull();
            assertThat(service.generateSessionSummary("   ")).isNull();

            when(callSpec.content()).thenReturn("{\"mainTopic\":\"考试焦虑\"}");
            assertThat(service.generateSessionSummary("学生：我紧张\nAI：说说看")).contains("mainTopic");

            doThrow(new RuntimeException("LLM error")).when(callSpec).content();
            assertThat(service.generateSessionSummary("任意文本")).isNull();
        }

        @Test
        @DisplayName("extractConversationInsights：空输入短路 / 双节点 JSON / 异常（S1 合并）")
        void extractConversationInsights() {
            assertThat(service.extractConversationInsights(null, "s")).isNull();
            assertThat(service.extractConversationInsights("", "s")).isNull();

            when(callSpec.content()).thenReturn("{\"profile_patch\":{\"resilience\":{}},\"key_events\":[]}");
            assertThat(service.extractConversationInsights("对话文本", null)).contains("profile_patch");
            assertThat(service.extractConversationInsights("对话文本", "摘要")).contains("key_events");

            doThrow(new RuntimeException("boom")).when(callSpec).content();
            assertThat(service.extractConversationInsights("对话文本", "摘要")).isNull();
        }

        @Test
        @DisplayName("evaluateConversationQuality：空输入短路 / 成功 / 异常")
        void evaluateConversationQuality() {
            assertThat(service.evaluateConversationQuality(null)).isNull();

            when(callSpec.content()).thenReturn("{\"empathy_score\":0.8}");
            assertThat(service.evaluateConversationQuality("完整对话")).contains("empathy_score");

            doThrow(new RuntimeException("boom")).when(callSpec).content();
            assertThat(service.evaluateConversationQuality("完整对话")).isNull();
        }

        @Test
        @DisplayName("summarizeSessionProgress：空输入短路 / 成功 / 异常")
        void summarizeSessionProgress() {
            assertThat(service.summarizeSessionProgress(null)).isNull();

            when(callSpec.content()).thenReturn("学生聊了考试压力，情绪由紧张转平静。");
            assertThat(service.summarizeSessionProgress("长对话")).contains("考试");

            doThrow(new RuntimeException("boom")).when(callSpec).content();
            assertThat(service.summarizeSessionProgress("长对话")).isNull();
        }
    }

    @Test
    @DisplayName("审计日志：错误消息超 500 字符截断；mapper 异常不影响业务")
    void auditLogTruncationAndResilience() {
        String longMsg = "x".repeat(600);
        doThrow(new RuntimeException(longMsg)).when(callSpec).content();

        assertThat(service.generateSessionSummary("对话")).isNull();

        ArgumentCaptor<ModelCallLog> captor = ArgumentCaptor.forClass(ModelCallLog.class);
        verify(modelCallLogMapper).insert(captor.capture());
        ModelCallLog log = captor.getValue();
        assertThat(log.getStatus()).isEqualTo("error");
        assertThat(log.getErrorMessage()).hasSize(500);

        // mapper 抛异常时业务仍正常返回（降级不影响业务）
        org.mockito.Mockito.doReturn("ok").when(callSpec).content();
        org.mockito.Mockito.reset(modelCallLogMapper);
        org.mockito.Mockito.doThrow(new RuntimeException("db down")).when(modelCallLogMapper).insert(org.mockito.ArgumentMatchers.any(ModelCallLog.class));
        assertThat(service.generateSessionSummary("对话")).isEqualTo("ok");
    }
}
