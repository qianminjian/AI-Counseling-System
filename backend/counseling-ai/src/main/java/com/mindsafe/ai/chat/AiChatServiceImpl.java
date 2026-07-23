package com.mindsafe.ai.chat;

import com.mindsafe.common.dto.chat.StreamMessageEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;

/**
 * AI 聊天服务实现（Spring AI ChatClient 流式调用）
 * <p>
 * M1 简化：单轮对话 + 基础系统提示词。
 * 后续迭代：Advisor 链（Safety/Memory/RAG）+ CBT 状态机。
 */
@Service
public class AiChatServiceImpl implements AiChatService {

    private static final Logger log = LoggerFactory.getLogger(AiChatServiceImpl.class);

    private final ChatClient chatClient;

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

    public AiChatServiceImpl(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    public Flux<StreamMessageEvent> chat(UUID sessionId, String emotionTag, String message) {
        log.debug("AI 对话请求: sessionId={}, emotion={}, msgLength={}", sessionId, emotionTag, message.length());

        String systemPrompt = SYSTEM_PROMPT.replace("{emotionTag}", emotionTag);

        return chatClient.prompt()
                .system(systemPrompt)
                .user(message)
                .stream()
                .content()
                .map(StreamMessageEvent::token)
                .doOnComplete(() -> log.debug("AI 回复完成: sessionId={}", sessionId))
                .doOnError(e -> log.error("AI 流式调用失败: sessionId={}", sessionId, e));
    }
}
