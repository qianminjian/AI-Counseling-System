package com.mindsafe.ai.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindsafe.ai.prompt.PromptTemplateService;
import com.mindsafe.ai.safety.OutputContentFilter;
import com.mindsafe.ai.safety.OutputReviewService;
import com.mindsafe.ai.safety.OutputSafetyReporter;
import com.mindsafe.ai.safety.SafetyKeywordLibrary;
import com.mindsafe.common.dto.chat.StreamMessageEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AiChatServiceImpl 单元测试（design/28 §三 3.4：主动暖场不污染对话记忆）
 * <p>
 * 关键断言：chatProactive 绝不向 ChatMemory 写入伪造的学生消息（UserMessage），
 * 仅把 nudge 指令追加到 system 层；AI 回复正常写入 AssistantMessage（孩子看到的连续性保留）。
 */
class AiChatServiceImplTest {

    private ChatMemory chatMemory;
    private OutputReviewService outputReviewService;
    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.StreamResponseSpec streamSpec;

    private AiChatServiceImpl service;

    private final UUID sessionId = UUID.randomUUID();
    private final String conversationId = sessionId.toString();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        chatMemory = mock(ChatMemory.class);
        outputReviewService = mock(OutputReviewService.class);

        // Layer1 用真实过滤器（词库未加载=空规则，正常文本纯透传，同 OutputContentFilter 行为）
        SafetyKeywordLibrary library = new SafetyKeywordLibrary(new ObjectMapper());
        OutputContentFilter outputContentFilter =
                new OutputContentFilter(library, mock(OutputSafetyReporter.class));

        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        streamSpec = mock(ChatClient.StreamResponseSpec.class);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.messages(anyList())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);

        service = new AiChatServiceImpl(builder, chatMemory, outputContentFilter,
                outputReviewService,
                new LlmStreamEnhancer(3000, 60000, 1, 2000, new SimpleMeterRegistry(), new PromptTemplateService()),
                mock(com.mindsafe.domain.mapper.ModelCallLogMapper.class), new SimpleMeterRegistry(),
                new PromptTemplateService(), Runnable::run);
    }

    @Test
    @DisplayName("chatProactive 不写伪造学生消息：仅写一条 AssistantMessage")
    @SuppressWarnings("unchecked")
    void chatProactive_doesNotPolluteMemory() {
        // 已有历史：孩子倾诉 + 波波回复
        when(chatMemory.get(conversationId)).thenReturn(List.of(
                new UserMessage("没人和我玩"),
                new AssistantMessage("听起来你有点孤单呢")));
        when(streamSpec.content()).thenReturn(Flux.just("波波在呢", "～"));

        List<StreamMessageEvent> events = service
                .chatProactive(sessionId, "sad", null, "【预渲染】SYS_001+语言模板+暖场指令")
                .collectList().block();

        assertThat(events).hasSize(2);
        assertThat(events.get(0).content()).isEqualTo("波波在呢");

        // 整个流程只向记忆写入一次：AI 回复（AssistantMessage），绝无伪造 UserMessage
        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatMemory, times(1)).add(eq(conversationId), captor.capture());
        List<Message> written = captor.getValue();
        assertThat(written).hasSize(1);
        assertThat(written.get(0)).isInstanceOf(AssistantMessage.class);
        assertThat(written.get(0).getText()).isEqualTo("波波在呢～");
        assertThat(written).noneMatch(m -> m instanceof UserMessage);

        // Layer2 异步 SAF-002 审查照常
        verify(outputReviewService).reviewAsync(eq(sessionId), eq("波波在呢～"), eq("sad"));
    }

    @Test
    @DisplayName("chatProactive 原样透传预解析 systemPromptContent（版本路由）+ contextBrief 尾部追加")
    void chatProactive_usesPreRenderedSystemPrompt() {
        when(chatMemory.get(conversationId)).thenReturn(List.of());
        when(streamSpec.content()).thenReturn(Flux.just("在呢"));

        service.chatProactive(sessionId, "happy", "【上下文简报】偏沉默",
                        "【预渲染】SYS_001+语言模板+TSK_004暖场指令")
                .collectList().block();

        ArgumentCaptor<String> sysCaptor = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).system(sysCaptor.capture());
        String system = sysCaptor.getValue();
        // 预渲染内容原样透传（B4：性别风格已由调用方经版本路由组装，本服务不再追加）
        assertThat(system).contains("【预渲染】SYS_001+语言模板+TSK_004暖场指令");
        // contextBrief 追加到 system 层尾部（recency bias）
        assertThat(system).contains("【上下文简报】偏沉默");
        assertThat(system.indexOf("【上下文简报】偏沉默"))
                .isGreaterThan(system.indexOf("【预渲染】SYS_001+语言模板+TSK_004暖场指令"));
    }

    @Test
    @DisplayName("降级话术（fallback 标记）不写入会话记忆、不触发 Layer2 审查（OPS-P2-01/doing/96）")
    @SuppressWarnings("unchecked")
    void fallbackToken_doesNotPolluteMemoryOrReview() {
        when(chatMemory.get(conversationId)).thenReturn(List.of());

        // 模拟 enhance 降级输出：fallback 标记 token + done（前端仍可见，数据面应排除）
        LlmStreamEnhancer enhancer = mock(LlmStreamEnhancer.class);
        Flux<StreamMessageEvent> fallbackFlux = Flux.just(
                StreamMessageEvent.fallback("网络开小差了，我们重试一下吧～"),
                StreamMessageEvent.done(null));
        when(enhancer.enhance(any(java.util.function.Supplier.class), any(UUID.class))).thenReturn(fallbackFlux);
        AiChatServiceImpl fallbackService = new AiChatServiceImpl(
                mock(ChatClient.Builder.class), chatMemory,
                mock(OutputContentFilter.class), outputReviewService, enhancer,
                mock(com.mindsafe.domain.mapper.ModelCallLogMapper.class), new SimpleMeterRegistry(),
                new PromptTemplateService(), Runnable::run);

        List<StreamMessageEvent> events = fallbackService
                .chatWithPrompt(sessionId, "sad", "孩子消息", "【预渲染】SYS_001")
                .collectList().block();

        // 降级话术仍以 token 形式透传给前端（孩子可见，不阻断响应）
        assertThat(events).hasSize(2);
        assertThat(events.get(0).type()).isEqualTo("token");
        assertThat(events.get(0).content()).isNotBlank();

        // 但不得写入会话记忆：chatMemory.add 仅记录前置用户消息，AI 降级话术不写
        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatMemory, times(1)).add(eq(conversationId), captor.capture());
        List<Message> written = captor.getValue();
        assertThat(written).hasSize(1);
        assertThat(written.get(0)).isInstanceOf(UserMessage.class);

        // 不触发 Layer2 审查（降级话术无审查价值，避免无效 LLM 调用与误报面）
        verify(outputReviewService, times(0)).reviewAsync(any(), any(), any());
    }
}
