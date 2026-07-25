package com.mindsafe.ai.agent;

import com.mindsafe.ai.prompt.PromptTemplateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Conversation Agent（对话交互）— 对齐 design/13 §2.4
 * <p>
 * 最终回复生成：整合 SYS-001 系统提示 + 语言适配（LANG）+ 对话历史，
 * 生成儿童友好的流式回复。低强度情绪走此 Agent（支持性回复），高强度走 CBTAgent。
 * <p>
 * 降级策略：LLM 失败时返回通用共情回复。
 */
@Component
public class ConversationAgent implements Agent<ConversationAgent.Input, ConversationAgent.Result> {

    private static final Logger log = LoggerFactory.getLogger(ConversationAgent.class);

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final PromptTemplateService promptTemplateService;

    public ConversationAgent(ChatClient.Builder chatClientBuilder,
                             ChatMemory chatMemory,
                             PromptTemplateService promptTemplateService) {
        this.chatClient = chatClientBuilder.build();
        this.chatMemory = chatMemory;
        this.promptTemplateService = promptTemplateService;
    }

    @Override
    public String agentName() {
        return "ConversationAgent";
    }

    @Override
    public Duration timeout() {
        return Duration.ofSeconds(60);
    }

    @Override
    public Result execute(Input input, ConversationContext context) {
        try {
            // 构建系统提示：SYS-001 + 语言适配
            String systemPrompt = promptTemplateService.render(PromptTemplateService.SYS_001, Map.of(
                    "grade_level", String.valueOf(context.gradeLevel()),
                    "emotion_tag", context.emotionTag(),
                    "school_policy", "默认：发现高风险立即通知心理老师。",
                    "session_mode", "normal_counseling"
            ));

            // 追加语言规则
            String langTemplate = PromptTemplateService.languageTemplateForGrade(context.gradeLevel());
            String langRules = promptTemplateService.getTemplate(langTemplate);
            String fullSystem = systemPrompt + "\n\n" + langRules;

            // 获取对话历史
            List<Message> history = chatMemory.get(context.sessionId().toString());

            // 流式调用由上层 AiChatService 处理，此处做同步调用（Orchestrator 场景）
            String reply = chatClient.prompt()
                    .system(fullSystem)
                    .messages(history)
                    .user(input.message())
                    .call()
                    .content();

            return new Result(reply, context.gradeLevel(), "llm");

        } catch (Exception e) {
            log.warn("ConversationAgent LLM 调用失败: {}", e.getMessage());
            return fallback(input, context, e);
        }
    }

    @Override
    public Result fallback(Input input, ConversationContext context, Throwable cause) {
        String fallbackReply = "我在这里陪着你。你的感受很重要，想和我多聊聊吗？";
        return new Result(fallbackReply, context.gradeLevel(), "fallback");
    }

    // ===== 输入/输出类型 =====

    public record Input(String message) {}

    public record Result(
            String reply,
            int languageLevel,
            String source
    ) {}
}
