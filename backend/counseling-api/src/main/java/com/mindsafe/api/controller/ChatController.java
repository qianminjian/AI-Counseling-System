package com.mindsafe.api.controller;

import com.mindsafe.api.dto.chat.*;
import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.dto.chat.SessionInfo;
import com.mindsafe.common.dto.chat.StreamMessageEvent;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.service.consent.GuardianConsentService;
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
    private final GuardianConsentService guardianConsentService;

    public ChatController(ConversationService conversationService,
                          GuardianConsentService guardianConsentService) {
        this.conversationService = conversationService;
        this.guardianConsentService = guardianConsentService;
    }

    /**
     * 创建对话会话（学生选择情绪后触发）
     */
    @PostMapping("/sessions")
    public ApiResponse<SessionInfo> createSession(
            @Valid @RequestBody CreateSessionRequest request,
            Authentication authentication) {
        TenantContext ctx = extractContext(authentication);
        requireGuardianConsent(ctx);

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
        requireGuardianConsent(ctx);

        if (request.hasVoiceEmotion()) {
            return conversationService.sendMessageStream(
                    ctx.tenantId(), sessionId, request.content(),
                    request.voiceEmotion(), request.voiceEmotionConfidence());
        }
        return conversationService.sendMessageStream(ctx.tenantId(), sessionId, request.content());
    }

    /**
     * 冷场暖场（nudge，SSE，design/28 §六 6.1）
     * <p>
     * 前端沉默检测满足后调用；后端冷场决策模型决定留白（返回空流，不打扰）
     * 或暖场（返回与 messages 相同的 SSE token 流，前端追加 AI 消息 + TTS 朗读）。
     */
    @PostMapping(value = "/sessions/{sessionId}/nudge", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<StreamMessageEvent> sendNudge(
            @PathVariable UUID sessionId,
            @Valid @RequestBody NudgeRequest request,
            Authentication authentication) {
        TenantContext ctx = extractContext(authentication);
        requireGuardianConsent(ctx);
        return conversationService.sendNudgeStream(ctx.tenantId(), sessionId, request.silenceSeconds());
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

    /**
     * 监护人同意门禁（R-03，PIPL §31 未成年人单独同意）
     * <p>
     * 学生进入对话（创建会话/发消息/暖场）前必须已完成监护人同意闭环，否则拒绝。
     */
    private void requireGuardianConsent(TenantContext ctx) {
        if (!guardianConsentService.hasGuardianConsent(ctx.tenantId(), ctx.userId())) {
            throw new BizException(ErrorCode.CONSENT_REQUIRED, "需要先完成监护人同意才能开始对话");
        }
    }
}
