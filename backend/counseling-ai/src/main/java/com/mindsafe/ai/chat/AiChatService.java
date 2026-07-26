package com.mindsafe.ai.chat;

import com.mindsafe.common.dto.chat.StreamMessageEvent;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * AI 聊天服务接口（Spring AI 封装）
 */
public interface AiChatService {

    /**
     * 流式对话（返回逐 token 的 SSE 事件流）
     *
     * @param sessionId     会话 ID
     * @param emotionTag    当前情绪标签
     * @param message       学生消息
     * @param gender        学生性别（male/female，可为 null）
     * @param profilePrompt 学生画像 Prompt 片段（可为 null）
     * @return 流式事件
     */
    Flux<StreamMessageEvent> chat(UUID sessionId, String emotionTag, String message, String gender, String profilePrompt);

    /**
     * 清除会话记忆（会话结束时调用）
     *
     * @param sessionId 会话 ID
     */
    void clearMemory(UUID sessionId);

    /**
     * 生成会话结构化摘要（非流式，会话关闭后异步调用）
     *
     * @param conversationText 对话摘要文本（学生+AI 各轮次）
     * @return JSON 格式摘要（mainTopic/emotionTrend/riskNote/suggestion）
     */
    String generateSessionSummary(String conversationText);

    /**
     * 提炼学生画像增量 patch（PROF-003，非流式，会话关闭后异步调用）
     * <p>
     * 仅输出结构化统计指标与泛化标签，严禁输出原始对话内容；人物代号化、主题泛化。
     *
     * @param conversationText 对话摘要文本
     * @param sessionSummary   会话结构化摘要（JSON）
     * @return JSON patch（communication_pref/resilience/social_graph），失败返回 null
     */
    String extractProfilePatch(String conversationText, String sessionSummary);
}
