package com.mindsafe.ai.chat;

import com.mindsafe.ai.prompt.PromptTemplateService;
import com.mindsafe.ai.safety.OutputContentFilter;
import com.mindsafe.ai.safety.OutputReviewService;
import com.mindsafe.common.dto.chat.StreamMessageEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI 聊天服务实现（Spring AI ChatClient 流式调用 + 多轮对话记忆 + 双层输出安全审查）
 * <p>
 * System Prompt 从 classpath 模板文件加载（SYS-001），运行时注入 emotion_tag 等变量。
 * 输出安全：Layer1 {@link OutputContentFilter} 流式实时硬过滤（命中即中断+安全话术）；
 * Layer2 {@link OutputReviewService} 流结束后异步 SAF-002 语义审查（检测+留痕，不阻塞）。
 */
@Service
public class AiChatServiceImpl implements AiChatService {

    private static final Logger log = LoggerFactory.getLogger(AiChatServiceImpl.class);

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final OutputContentFilter outputContentFilter;
    private final OutputReviewService outputReviewService;
    private final PromptTemplateService promptTemplateService;

    public AiChatServiceImpl(ChatClient.Builder chatClientBuilder, ChatMemory chatMemory,
                             OutputContentFilter outputContentFilter, OutputReviewService outputReviewService,
                             PromptTemplateService promptTemplateService) {
        this.chatClient = chatClientBuilder.build();
        this.chatMemory = chatMemory;
        this.outputContentFilter = outputContentFilter;
        this.outputReviewService = outputReviewService;
        this.promptTemplateService = promptTemplateService;
    }

    @Override
    public Flux<StreamMessageEvent> chat(UUID sessionId, String emotionTag, String message) {
        String conversationId = sessionId.toString();
        log.debug("AI 对话请求: sessionId={}, emotion={}, msgLength={}", sessionId, emotionTag, message.length());

        // 1. 保存用户消息到记忆
        chatMemory.add(conversationId, List.of(new UserMessage(message)));

        // 2. 获取历史消息构建上下文（窗口大小由 MessageWindowChatMemory 配置控制）
        List<Message> history = chatMemory.get(conversationId);

        // 3. 从模板文件加载 System Prompt（SYS-001），注入运行时变量
        String systemPrompt = promptTemplateService.render(PromptTemplateService.SYS_001, Map.of(
                "grade_level", "5-6",
                "emotion_tag", emotionTag,
                "school_policy", "默认：发现高风险立即通知心理老师。",
                "session_mode", "normal_counseling"
        ));

        // 4. 流式调用 LLM（带历史上下文）
        StringBuilder responseCollector = new StringBuilder();

        Flux<String> rawTokens = chatClient.prompt()
                .system(systemPrompt)
                .messages(history)
                .stream()
                .content();

        // 5. Layer1 实时过滤：命中 block 级敏感词时中断流并替换为安全话术
        return outputContentFilter.apply(rawTokens, sessionId)
                .doOnNext(evt -> {
                    if ("token".equals(evt.type()) && evt.content() != null) {
                        responseCollector.append(evt.content());
                    }
                })
                .doOnComplete(() -> {
                    // 6. 流结束后保存 AI 回复到记忆（含被拦截时的安全话术，即孩子实际看到的内容）
                    String fullReply = responseCollector.toString();
                    chatMemory.add(conversationId, List.of(new AssistantMessage(fullReply)));
                    log.debug("AI 回复完成: sessionId={}, responseLength={}", sessionId, fullReply.length());

                    // 7. Layer2 异步 SAF-002 语义审查（fire-and-forget，不阻塞主流）
                    outputReviewService.reviewAsync(sessionId, fullReply, emotionTag);
                })
                .doOnError(e -> log.error("AI 流式调用失败: sessionId={}", sessionId, e));
    }

    /** 清除会话记忆（会话结束时调用） */
    @Override
    public void clearMemory(UUID sessionId) {
        chatMemory.clear(sessionId.toString());
        log.debug("会话记忆已清除: sessionId={}", sessionId);
    }
}
