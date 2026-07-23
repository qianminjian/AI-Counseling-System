package com.mindsafe.api.controller;

import com.mindsafe.api.dto.chat.*;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.common.dto.chat.SessionInfo;
import com.mindsafe.common.dto.chat.StreamMessageEvent;
import com.mindsafe.service.conversation.ConversationService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * 学生对话 API（M1 核心）
 */
@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    private final ConversationService conversationService;

    public ChatController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    /**
     * 创建对话会话（学生选择情绪后触发）
     */
    @PostMapping("/sessions")
    public ApiResponse<SessionInfo> createSession(
            @Valid @RequestBody CreateSessionRequest request) {
        // TODO: 从 SecurityContext 获取当前学生 userId / tenantId
        UUID studentUserId = UUID.fromString("20000000-0000-0000-0000-000000000001");
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        SessionInfo response = conversationService.createSession(
                tenantId, studentUserId, request.emotionTag(), request.channel());
        return ApiResponse.ok(response);
    }

    /**
     * 发送消息并获取 AI 流式回复（SSE）
     * M2：支持语音情绪元数据
     */
    @PostMapping(value = "/sessions/{sessionId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<StreamMessageEvent> sendMessage(
            @PathVariable UUID sessionId,
            @Valid @RequestBody SendMessageRequest request) {
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        if (request.hasVoiceEmotion()) {
            return conversationService.sendMessageStream(
                    tenantId, sessionId, request.content(),
                    request.voiceEmotion(), request.voiceEmotionConfidence());
        }
        return conversationService.sendMessageStream(tenantId, sessionId, request.content());
    }

    /**
     * 结束会话
     */
    @PostMapping("/sessions/{sessionId}/end")
    public ApiResponse<Void> endSession(@PathVariable UUID sessionId) {
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        conversationService.endSession(tenantId, sessionId);
        return ApiResponse.ok();
    }
}
