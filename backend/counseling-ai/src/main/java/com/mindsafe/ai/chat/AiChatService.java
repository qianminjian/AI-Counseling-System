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
     * @param sessionId  会话 ID
     * @param emotionTag 当前情绪标签
     * @param message    学生消息
     * @return 流式事件
     */
    Flux<StreamMessageEvent> chat(UUID sessionId, String emotionTag, String message);

    /**
     * 清除会话记忆（会话结束时调用）
     *
     * @param sessionId 会话 ID
     */
    void clearMemory(UUID sessionId);
}
