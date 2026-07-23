package com.mindsafe.service.conversation;

import com.mindsafe.common.dto.chat.SessionInfo;
import com.mindsafe.common.dto.chat.StreamMessageEvent;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * 对话服务接口（M1 核心链路）
 */
public interface ConversationService {

    /**
     * 创建辅导会话
     */
    SessionInfo createSession(UUID tenantId, UUID studentUserId, String emotionTag, String channel);

    /**
     * 发送消息并获取 AI 流式回复
     */
    Flux<StreamMessageEvent> sendMessageStream(UUID tenantId, UUID sessionId, String content);

    /**
     * 结束会话
     */
    void endSession(UUID tenantId, UUID sessionId);
}
