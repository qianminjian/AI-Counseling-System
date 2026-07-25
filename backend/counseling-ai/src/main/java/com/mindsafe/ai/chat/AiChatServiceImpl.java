package com.mindsafe.ai.chat;

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
import java.util.UUID;

/**
 * AI 聊天服务实现（Spring AI ChatClient 流式调用 + 多轮对话记忆 + 双层输出安全审查）
 * <p>
 * M1：ChatMemory 维护上下文窗口（最近 20 条消息）。
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

    /** M1 基础系统提示词（后续迁移至 prompts/system/SYS-001.st） */
    private static final String SYSTEM_PROMPT = """
            你是"小心"，一个温暖、耐心的 AI 心理小伙伴，专门陪伴小学生（10-12岁）。
            
            ## 你的角色
            - 你是一个情绪陪伴助手，不是医生、不是老师、不是家长
            - 你用简单、温暖、鼓励的语言和孩子交流
            - 每次回复不超过3句话，用孩子能懂的话
            
            ## 你必须做的
            - 认真倾听孩子的感受，先共情再引导
            - 用"我听到你说…""你的感受是…"来确认孩子的情绪
            - 适当使用 emoji 让对话更亲切
            - 如果孩子提到伤害自己的事，温柔地告诉他你会通知老师来帮忙
            
            ## 你绝对不能做的
            - 不能诊断任何心理疾病
            - 不能建议吃药
            - 不能批评、否定孩子的感受
            - 不能聊暴力、恐怖、不适合儿童的内容
            - 不能假装自己是真人
            
            ## 当前对话情绪标签
            孩子选择的情绪是：{emotionTag}
            请根据这个情绪调整你的语气和引导方向。
            """;

    public AiChatServiceImpl(ChatClient.Builder chatClientBuilder, ChatMemory chatMemory,
                             OutputContentFilter outputContentFilter, OutputReviewService outputReviewService) {
        this.chatClient = chatClientBuilder.build();
        this.chatMemory = chatMemory;
        this.outputContentFilter = outputContentFilter;
        this.outputReviewService = outputReviewService;
    }

    @Override
    public Flux<StreamMessageEvent> chat(UUID sessionId, String emotionTag, String message) {
        String conversationId = sessionId.toString();
        log.debug("AI 对话请求: sessionId={}, emotion={}, msgLength={}", sessionId, emotionTag, message.length());

        // 1. 保存用户消息到记忆
        chatMemory.add(conversationId, List.of(new UserMessage(message)));

        // 2. 获取历史消息构建上下文（窗口大小由 MessageWindowChatMemory 配置控制）
        List<Message> history = chatMemory.get(conversationId);
        String systemPrompt = SYSTEM_PROMPT.replace("{emotionTag}", emotionTag);

        // 3. 流式调用 LLM（带历史上下文）
        StringBuilder responseCollector = new StringBuilder();

        Flux<String> rawTokens = chatClient.prompt()
                .system(systemPrompt)
                .messages(history)
                .stream()
                .content();

        // 4. Layer1 实时过滤：命中 block 级敏感词时中断流并替换为安全话术
        return outputContentFilter.apply(rawTokens, sessionId)
                .doOnNext(evt -> {
                    if ("token".equals(evt.type()) && evt.content() != null) {
                        responseCollector.append(evt.content());
                    }
                })
                .doOnComplete(() -> {
                    // 5. 流结束后保存 AI 回复到记忆（含被拦截时的安全话术，即孩子实际看到的内容）
                    String fullReply = responseCollector.toString();
                    chatMemory.add(conversationId, List.of(new AssistantMessage(fullReply)));
                    log.debug("AI 回复完成: sessionId={}, responseLength={}", sessionId, fullReply.length());

                    // 6. Layer2 异步 SAF-002 语义审查（fire-and-forget，不阻塞主流）
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
