package com.mindsafe.ai.agent;

import com.mindsafe.ai.prompt.PromptTemplateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * Emotion Agent（情绪识别）— 对齐 design/13 §2.2
 * <p>
 * LLM 驱动的情绪分类：类别、强度(0-10)、趋势。
 * 降级策略：LLM 失败时返回基于 emotionTag 的默认情绪。
 */
@Component
public class EmotionAgent implements Agent<EmotionAgent.Input, EmotionAgent.Result> {

    private static final Logger log = LoggerFactory.getLogger(EmotionAgent.class);

    private final ChatClient chatClient;
    private final PromptTemplateService promptTemplateService;

    public EmotionAgent(ChatClient.Builder chatClientBuilder, PromptTemplateService promptTemplateService) {
        this.chatClient = chatClientBuilder.build();
        this.promptTemplateService = promptTemplateService;
    }

    @Override
    public String agentName() {
        return "EmotionAgent";
    }

    @Override
    public Duration timeout() {
        return Duration.ofSeconds(10);
    }

    @Override
    public Result execute(Input input, ConversationContext context) {
        try {
            String prompt = buildEmotionPrompt(input, context);
            String llmResponse = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            return parseEmotionResult(llmResponse, input);
        } catch (Exception e) {
            log.warn("EmotionAgent LLM 调用失败，使用默认情绪: {}", e.getMessage());
            return fallback(input, context, e);
        }
    }

    @Override
    public Result fallback(Input input, ConversationContext context, Throwable cause) {
        // 降级：基于用户选择的情绪标签返回默认值
        String defaultEmotion = mapEmotionTag(context.emotionTag());
        return new Result(defaultEmotion, 5, "stable", 0.6, "fallback");
    }

    private String buildEmotionPrompt(Input input, ConversationContext context) {
        return """
                分析以下小学生消息的情绪状态，输出严格 JSON。
                
                消息：%s
                用户选择的情绪标签：%s
                年级：%d
                
                输出格式（严格 JSON，不要输出其他内容）：
                {"primary_emotion":"sad|happy|angry|scared|nervous|calm|surprised","intensity":0-10,"trend":"improving|stable|worsening","confidence":0.0-1.0}
                """.formatted(input.message(), context.emotionTag(), context.gradeLevel());
    }

    private Result parseEmotionResult(String llmResponse, Input input) {
        if (llmResponse == null) return new Result("calm", 3, "stable", 0.5, "default");

        // 简化解析
        String emotion = "calm";
        int intensity = 5;
        String trend = "stable";

        if (llmResponse.contains("\"sad\"")) emotion = "sad";
        else if (llmResponse.contains("\"angry\"")) emotion = "angry";
        else if (llmResponse.contains("\"scared\"")) emotion = "scared";
        else if (llmResponse.contains("\"happy\"")) emotion = "happy";
        else if (llmResponse.contains("\"nervous\"")) emotion = "nervous";

        if (llmResponse.contains("\"worsening\"")) trend = "worsening";
        else if (llmResponse.contains("\"improving\"")) trend = "improving";

        // 提取 intensity 数字
        try {
            int idx = llmResponse.indexOf("\"intensity\"");
            if (idx >= 0) {
                String sub = llmResponse.substring(idx);
                int colonIdx = sub.indexOf(':');
                if (colonIdx >= 0) {
                    String numStr = sub.substring(colonIdx + 1).replaceAll("[^0-9]", "").substring(0, 1);
                    intensity = Integer.parseInt(numStr);
                }
            }
        } catch (Exception ignored) {}

        return new Result(emotion, intensity, trend, 0.8, "llm");
    }

    private String mapEmotionTag(String emotionTag) {
        return switch (emotionTag) {
            case "happy" -> "happy";
            case "sad" -> "sad";
            case "angry" -> "angry";
            case "scared" -> "scared";
            case "nervous" -> "nervous";
            default -> "calm";
        };
    }

    // ===== 输入/输出类型 =====

    public record Input(String message) {}

    public record Result(
            String primaryEmotion,
            int intensity,
            String trend,
            double confidence,
            String source
    ) {
        /** 高强度情绪（>=7）路由到 CBT */
        public boolean isHighIntensity() {
            return intensity >= 7;
        }
    }
}
