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
    public Flux<StreamMessageEvent> chat(UUID sessionId, String emotionTag, String message, String gender) {
        String conversationId = sessionId.toString();
        log.debug("AI 对话请求: sessionId={}, emotion={}, gender={}, msgLength={}", sessionId, emotionTag, gender, message.length());

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

        // 3.5 性别个性化话术注入
        String genderStyle = buildGenderStyle(gender);
        String fullSystem = systemPrompt + "\n\n" + genderStyle;

        // 4. 流式调用 LLM（带历史上下文）
        StringBuilder responseCollector = new StringBuilder();

        Flux<String> rawTokens = chatClient.prompt()
                .system(fullSystem)
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

    private static final String SUMMARY_SYSTEM_PROMPT = """
            你是一位学校心理辅导系统的摘要生成器。根据以下对话记录，生成一份结构化 JSON 摘要，供心理老师快速了解会话情况。
            
            输出格式（严格 JSON，无其他文字）：
            {
              "mainTopic": "主要话题（10字以内）",
              "emotionTrend": "情绪变化趋势（20字以内）",
              "keyPoints": ["关键点1", "关键点2"],
              "riskNote": "风险提示（无风险则填'无'）",
              "suggestion": "给老师的建议（30字以内）"
            }
            
            注意：
            - 语言简洁专业，面向教师
            - 不暴露学生真实姓名
            - riskNote 只在发现自伤/被欺凌/家庭暴力等信号时填写
            """;

    @Override
    public String generateSessionSummary(String conversationText) {
        if (conversationText == null || conversationText.isBlank()) {
            return null;
        }
        try {
            String result = chatClient.prompt()
                    .system(SUMMARY_SYSTEM_PROMPT)
                    .user("请为以下对话生成摘要：\n\n" + conversationText)
                    .call()
                    .content();
            log.debug("会话摘要生成完成, length={}", result != null ? result.length() : 0);
            return result;
        } catch (Exception e) {
            log.error("会话摘要生成失败", e);
            return null;
        }
    }

    /** 根据性别构建个性化沟通风格 Prompt 片段 */
    private String buildGenderStyle(String gender) {
        if ("male".equals(gender)) {
            return """
                    # 沟通风格（男生）
                    - 你像一个阳光、爱运动的小队友，说话简短有力、带点幽默
                    - 多用行动隐喻："咱们想个办法试试？""你已经很勇敢了"
                    - CBT 切入顺序：情境 → 行动 → 感受（先做再感受）
                    - 情绪命名简洁："看起来你有点不爽？"
                    - 鼓励方向：勇气、坚持、想办法
                    """;
        } else if ("female".equals(gender)) {
            return """
                    # 沟通风格（女生）
                    - 你像一个暖心、细心的好闺蜜，说话温柔、有耐心
                    - 多用情感反射："听起来你心里有点委屈对吗？""你的感受很重要"
                    - CBT 切入顺序：感受 → 情境 → 行动（先命名情绪再解决）
                    - 情绪命名细腻："你是不是觉得有点孤单，又有点生气？"
                    - 鼓励方向：表达、自我关怀、愿意说出来就很棒
                    """;
        }
        // 未指定性别时使用通用风格
        return """
                # 沟通风格
                - 说话温和、有耐心，用小朋友能听懂的短句
                - 先共情，再帮助说出感受，再给一个小行动
                """;
    }
}
