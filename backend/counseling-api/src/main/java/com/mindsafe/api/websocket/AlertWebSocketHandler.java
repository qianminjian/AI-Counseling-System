package com.mindsafe.api.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindsafe.api.security.JwtTokenProvider;
import com.mindsafe.service.auth.TokenBlacklistService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 教师端预警实时推送 WebSocket Handler
 * <p>
 * 连接地址：ws://host/ws/alerts?token=JWT
 * 认证：从 query param 中解析 JWT，三重校验（access 类型 + 非黑名单 + 教师/管理员角色），
 * 提取 tenantId + userId
 * 推送：按 tenantId 分组，新风险事件秒级推送到同租户所有在线教师
 */
@Component
public class AlertWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AlertWebSocketHandler.class);

    /** 允许接入预警推送的角色（与 SecurityConfig /api/v1/alerts/** 对齐，学生/家长严禁接入） */
    private static final Set<String> ALERT_ROLES = Set.of("teacher", "psych_teacher", "class_teacher", "admin");

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
    public void afterConnectionEstablished(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null) { closeQuietly(session); return; }

        // 从 query param 提取 token，三重校验与 JwtAuthenticationFilter 对齐（SEC-002）
        String token = extractParam(uri, "token");
        if (token == null
                || !jwtTokenProvider.validateToken(token)
                || !jwtTokenProvider.isAccessToken(token)
                || blacklistService.isBlacklisted(token)) {
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
        // 心跳 pong 响应
        if ("ping".equals(message.getPayload())) {
            try { session.sendMessage(new TextMessage("pong")); } catch (IOException ignored) {}
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

    private String extractParam(URI uri, String name) {
        String query = uri.getQuery();
        if (query == null) return null;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(name)) return kv[1];
        }
        return null;
    }

    private void closeQuietly(WebSocketSession session) {
        try { session.close(CloseStatus.POLICY_VIOLATION); } catch (IOException ignored) {}
    }
}
