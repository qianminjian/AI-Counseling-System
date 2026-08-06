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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.time.format.DateTimeParseException;
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

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ConversationService conversationService;
    private final GuardianConsentService guardianConsentService;
    /** ARCH-010 D5（OVD-4）：旧关闭接口 TTL 到期时间（ISO-8601）；空 = 未设置（TTL 窗口内） */
    private final String endSessionExpiresAt;

    public ChatController(ConversationService conversationService,
                          GuardianConsentService guardianConsentService,
                          @Value("${mindsafe.deprecated.end-session.expires-at:}") String endSessionExpiresAt) {
        this.conversationService = conversationService;
        this.guardianConsentService = guardianConsentService;
        this.endSessionExpiresAt = endSessionExpiresAt;
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

        // 同步前端设置状态（TTS静音/唤醒开关），让 AI 知道自己的能力边界
        if (request.ttsMuted() != null || request.wakeEnabled() != null) {
            conversationService.updateClientSettings(ctx.tenantId(), ctx.userId(), sessionId, request.ttsMuted(), request.wakeEnabled());
        }

        if (request.hasVoiceEmotion()) {
            return conversationService.sendMessageStream(
                    ctx.tenantId(), ctx.userId(), sessionId, request.content(),
                    request.voiceEmotion(), request.voiceEmotionConfidence());
        }
        return conversationService.sendMessageStream(ctx.tenantId(), ctx.userId(), sessionId, request.content());
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
        return conversationService.sendNudgeStream(ctx.tenantId(), ctx.userId(), sessionId, request.silenceSeconds());
    }

    /**
     * 结束会话（旧关闭接口，ARCH-010 D5：TTL 到期后下线）
     * <p>
     * 新接口：POST /api/v1/sessions/{id}/close（支持满意度评价）。
     * 90 天 TTL 窗口（2026-08-06 起）内保留并记录调用日志供灰度观测；
     * 到期（配置 mindsafe.deprecated.end-session.expires-at）后返回 410。
     */
    @PostMapping("/sessions/{sessionId}/end")
    public ApiResponse<Void> endSession(@PathVariable UUID sessionId,
                                        Authentication authentication) {
        ensureEndSessionAlive();
        TenantContext ctx = extractContext(authentication);
        log.warn("DEPRECATED-API（TTL 窗口内，到期下线）: POST /api/v1/chat/sessions/{}/end, tenantId={}",
                sessionId, ctx.tenantId());
        conversationService.endSession(ctx.tenantId(), ctx.userId(), sessionId);
        return ApiResponse.ok();
    }

    /** 旧关闭接口 TTL：超过 expires-at 即下线（410）；配置非法时防御降级为未到期 */
    private void ensureEndSessionAlive() {
        if (endSessionExpiresAt == null || endSessionExpiresAt.isBlank()) {
            return;
        }
        try {
            if (Instant.parse(endSessionExpiresAt).isBefore(Instant.now())) {
                throw new BizException(ErrorCode.API_GONE, "旧关闭接口已下线，请使用 POST /api/v1/sessions/{id}/close");
            }
        } catch (DateTimeParseException e) {
            log.error("mindsafe.deprecated.end-session.expires-at 配置非法，视为未到期: {}", endSessionExpiresAt, e);
        }
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
