package com.mindsafe.api.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindsafe.api.security.JwtTokenProvider;
import com.mindsafe.domain.entity.User;
import com.mindsafe.service.auth.TokenBlacklistService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 教师端预警实时推送 WebSocket Handler
 * <p>
 * 连接地址：ws://host/ws/alerts
 * 认证：JWT 由 AlertAuthHandshakeInterceptor 从握手头 Sec-WebSocket-Protocol（auth.&lt;jwt&gt; 项）
 * 提取后写入 attributes，本 handler 统一做三重校验（access 类型 + 非黑名单 + 教师/管理员角色），
 * 提取 tenantId + userId（P1-FE-4：JWT 不进 query string，避免入 nginx access log）
 * 推送：按 tenantId 分组，新风险事件秒级推送到同租户所有在线教师
 */
@Component
public class AlertWebSocketHandler extends TextWebSocketHandler implements SubProtocolCapable {

    private static final Logger log = LoggerFactory.getLogger(AlertWebSocketHandler.class);

    /** 协商子协议：前端连接必须携带该标识（与 auth.&lt;jwt&gt; 项一并提交） */
    private static final List<String> SUB_PROTOCOLS = List.of("alerts.v1");

    /** 允许接入预警推送的角色（与 SecurityConfig /api/v1/alerts/** 对齐，学生/家长严禁接入） */
    private static final Set<String> ALERT_ROLES = Set.of(User.USER_TYPE_TEACHER, User.USER_TYPE_PSYCH_TEACHER, User.USER_TYPE_CLASS_TEACHER, User.USER_TYPE_HEAD_TEACHER, User.USER_TYPE_ADMIN);

    /** tenantId → 该租户下所有在线教师 session */
    private final Map<UUID, Set<WebSocketSession>> tenantSessions = new ConcurrentHashMap<>();

    private final JwtTokenProvider jwtTokenProvider;
    private final TokenBlacklistService blacklistService;
    private final ObjectMapper objectMapper;

    public AlertWebSocketHandler(JwtTokenProvider jwtTokenProvider,
                                 TokenBlacklistService blacklistService,
                                 ObjectMapper objectMapper) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.blacklistService = blacklistService;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<String> getSubProtocols() {
        return SUB_PROTOCOLS;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // token 由 AlertAuthHandshakeInterceptor 在握手时从 Sec-WebSocket-Protocol 提取（P1-FE-4）
        String token = (String) session.getAttributes().get(AlertAuthHandshakeInterceptor.TOKEN_ATTR);
        if (token == null || token.isBlank()
                || !jwtTokenProvider.validateToken(token)
                || !jwtTokenProvider.isAccessToken(token)
                || blacklistService.isBlacklisted(jwtTokenProvider.getTokenId(token))) {
            log.warn("WebSocket 连接拒绝：无效/非 access/已登出 token");
            closeQuietly(session);
            return;
        }

        // 角色检查：仅教师/管理员可接收预警（学生 token 接入会泄漏全租户未成年人预警数据）
        String userType = jwtTokenProvider.getUserType(token);
        if (userType == null || !ALERT_ROLES.contains(userType)) {
            log.warn("WebSocket 连接拒绝：角色无权接收预警, userType={}", userType);
            closeQuietly(session);
            return;
        }

        UUID tenantId = jwtTokenProvider.getTenantId(token);
        UUID userId = jwtTokenProvider.getUserId(token);
        if (tenantId == null || userId == null) {
            closeQuietly(session);
            return;
        }

        session.getAttributes().put("tenantId", tenantId);
        session.getAttributes().put("userId", userId);
        tenantSessions.computeIfAbsent(tenantId, k -> new CopyOnWriteArraySet<>()).add(session);

        log.info("教师 WebSocket 已连接: tenant={}, user={}, 当前在线={}",
                tenantId, userId, tenantSessions.get(tenantId).size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        UUID tenantId = (UUID) session.getAttributes().get("tenantId");
        if (tenantId != null) {
            Set<WebSocketSession> sessions = tenantSessions.get(tenantId);
            if (sessions != null) {
                sessions.remove(session);
                if (sessions.isEmpty()) tenantSessions.remove(tenantId);
            }
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // 心跳 pong 响应（ARCH-010 P2-5 审计：session 已断连时忽略，属合理吞没）
        if ("ping".equals(message.getPayload())) {
            try { session.sendMessage(new TextMessage("pong")); } catch (IOException ignored) { /* 对端已断开，忽略心跳响应 */ }
        }
    }

    /**
     * 向指定租户的所有在线教师推送预警消息
     */
    public void pushAlert(UUID tenantId, Map<String, Object> payload) {
        Set<WebSocketSession> sessions = tenantSessions.get(tenantId);
        if (sessions == null || sessions.isEmpty()) return;

        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.error("WebSocket 消息序列化失败", e);
            return;
        }

        TextMessage msg = new TextMessage(json);
        for (WebSocketSession session : sessions) {
            if (session.isOpen()) {
                try {
                    session.sendMessage(msg);
                } catch (IOException e) {
                    log.warn("WebSocket 推送失败，移除 session: {}", e.getMessage());
                    sessions.remove(session);
                }
            } else {
                sessions.remove(session);
            }
        }
    }

    /** 获取指定租户在线教师数 */
    public int getOnlineCount(UUID tenantId) {
        Set<WebSocketSession> sessions = tenantSessions.get(tenantId);
        return sessions != null ? sessions.size() : 0;
    }

    private void closeQuietly(WebSocketSession session) {
        // ARCH-010 P2-5 审计：主动关闭时的 IOException 无可恢复路径，忽略（session 即将销毁）
        try { session.close(CloseStatus.POLICY_VIOLATION); } catch (IOException ignored) { /* 关闭失败无恢复路径，忽略 */ }
    }
}
