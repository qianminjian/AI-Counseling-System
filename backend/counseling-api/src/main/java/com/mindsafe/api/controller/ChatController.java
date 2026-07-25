package com.mindsafe.api.controller;

import com.mindsafe.api.dto.chat.*;
import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.dto.chat.SessionInfo;
import com.mindsafe.common.dto.chat.StreamMessageEvent;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.service.conversation.ConversationService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * 学生对话 API（M1 核心）
 * <p>
 * 身份从 SecurityContext 获取（JwtAuthenticationFilter 注入 TenantContext），
 * 不再硬编码 userId / tenantId。
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
            @Valid @RequestBody CreateSessionRequest request,
            Authentication authentication) {
        TenantContext ctx = extractContext(authentication);

        SessionInfo response = conversationService.createSession(
                ctx.tenantId(), ctx.userId(), request.emotionTag(), request.channel());
        return ApiResponse.ok(response);
    }

    /**
     * 发送消息并获取 AI 流式回复（SSE）
     * M2：支持语音情绪元数据
     */
    @PostMapping(value = "/sessions/{sessionId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<StreamMessageEvent> sendMessage(
            @PathVariable UUID sessionId,
            @Valid @RequestBody SendMessageRequest request,
            Authentication authentication) {
        TenantContext ctx = extractContext(authentication);

        if (request.hasVoiceEmotion()) {
            return conversationService.sendMessageStream(
                    ctx.tenantId(), sessionId, request.content(),
                    request.voiceEmotion(), request.voiceEmotionConfidence());
        }
        return conversationService.sendMessageStream(ctx.tenantId(), sessionId, request.content());
    }

    /**
     * 结束会话
     */
    @PostMapping("/sessions/{sessionId}/end")
    public ApiResponse<Void> endSession(@PathVariable UUID sessionId,
                                        Authentication authentication) {
        TenantContext ctx = extractContext(authentication);
        conversationService.endSession(ctx.tenantId(), sessionId);
        return ApiResponse.ok();
    }

    /** 从 Authentication 提取租户上下文 */
    private TenantContext extractContext(Authentication authentication) {
        if (authentication == null || !(authentication.getDetails() instanceof TenantContext ctx)) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return ctx;
    }
}
