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
}
