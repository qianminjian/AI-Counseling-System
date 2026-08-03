package com.mindsafe.api.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindsafe.api.security.JwtTokenProvider;
import com.mindsafe.service.auth.TokenBlacklistService;
import com.mindsafe.service.notification.RiskAlertPushEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * AlertWebSocketHandler 单元测试（13/20 篇审计补齐：api.websocket 0%→80%）
 * 覆盖：三重认证拒绝路径、角色门禁、租户分组、心跳、推送与故障 session 清理
 */
class AlertWebSocketHandlerTest {

    private JwtTokenProvider jwtTokenProvider;
    private TokenBlacklistService blacklistService;
    private AlertWebSocketHandler handler;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jwtTokenProvider = mock(JwtTokenProvider.class);
        blacklistService = mock(TokenBlacklistService.class);
        handler = new AlertWebSocketHandler(jwtTokenProvider, blacklistService, new ObjectMapper());
    }

    private WebSocketSession mockSession(String uri) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getUri()).thenReturn(uri == null ? null : URI.create(uri));
        when(session.getAttributes()).thenReturn(new HashMap<>());
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    private void stubValidTeacherToken(String token) {
        when(jwtTokenProvider.validateToken(token)).thenReturn(true);
        when(jwtTokenProvider.isAccessToken(token)).thenReturn(true);
        when(blacklistService.isBlacklisted(token)).thenReturn(false);
        when(jwtTokenProvider.getUserType(token)).thenReturn("teacher");
        when(jwtTokenProvider.getTenantId(token)).thenReturn(tenantId);
        when(jwtTokenProvider.getUserId(token)).thenReturn(userId);
    }

    // ===== 连接认证：拒绝路径 =====

    @Test
    @DisplayName("URI 为 null → 静默关闭")
    void rejectNullUri() throws IOException {
        WebSocketSession session = mockSession(null);
        handler.afterConnectionEstablished(session);
        verify(session).close(CloseStatus.POLICY_VIOLATION);
        assertEquals(0, handler.getOnlineCount(tenantId));
    }

    @Test
    @DisplayName("无 token 参数 → 拒绝")
    void rejectMissingToken() throws IOException {
        WebSocketSession session = mockSession("ws://host/ws/alerts");
        handler.afterConnectionEstablished(session);
        verify(session).close(CloseStatus.POLICY_VIOLATION);
        verifyNoInteractions(jwtTokenProvider);
    }

    @Test
    @DisplayName("token 无效 → 拒绝且不继续校验")
    void rejectInvalidToken() throws IOException {
        WebSocketSession session = mockSession("ws://host/ws/alerts?token=bad");
        when(jwtTokenProvider.validateToken("bad")).thenReturn(false);

        handler.afterConnectionEstablished(session);

        verify(session).close(CloseStatus.POLICY_VIOLATION);
        verify(jwtTokenProvider, never()).isAccessToken(any());
    }

    @Test
    @DisplayName("refresh token（非 access）→ 拒绝")
    void rejectNonAccessToken() throws IOException {
        WebSocketSession session = mockSession("ws://host/ws/alerts?token=rf");
        when(jwtTokenProvider.validateToken("rf")).thenReturn(true);
        when(jwtTokenProvider.isAccessToken("rf")).thenReturn(false);

        handler.afterConnectionEstablished(session);

        verify(session).close(CloseStatus.POLICY_VIOLATION);
        verify(blacklistService, never()).isBlacklisted(any());
    }

    @Test
    @DisplayName("黑名单 token（已登出）→ 拒绝")
    void rejectBlacklistedToken() throws IOException {
        WebSocketSession session = mockSession("ws://host/ws/alerts?token=bl");
        when(jwtTokenProvider.validateToken("bl")).thenReturn(true);
        when(jwtTokenProvider.isAccessToken("bl")).thenReturn(true);
        when(blacklistService.isBlacklisted("bl")).thenReturn(true);

        handler.afterConnectionEstablished(session);

        verify(session).close(CloseStatus.POLICY_VIOLATION);
        verify(jwtTokenProvider, never()).getUserType(any());
    }

    @Test
    @DisplayName("学生角色接入 → 拒绝（防全租户未成年人预警泄漏）")
    void rejectStudentRole() throws IOException {
        WebSocketSession session = mockSession("ws://host/ws/alerts?token=stu");
        when(jwtTokenProvider.validateToken("stu")).thenReturn(true);
        when(jwtTokenProvider.isAccessToken("stu")).thenReturn(true);
        when(blacklistService.isBlacklisted("stu")).thenReturn(false);
        when(jwtTokenProvider.getUserType("stu")).thenReturn("student");

        handler.afterConnectionEstablished(session);

        verify(session).close(CloseStatus.POLICY_VIOLATION);
        assertEquals(0, handler.getOnlineCount(tenantId));
    }

    @Test
    @DisplayName("userType 为 null → 拒绝")
    void rejectNullUserType() throws IOException {
        WebSocketSession session = mockSession("ws://host/ws/alerts?token=nt");
        when(jwtTokenProvider.validateToken("nt")).thenReturn(true);
        when(jwtTokenProvider.isAccessToken("nt")).thenReturn(true);
        when(blacklistService.isBlacklisted("nt")).thenReturn(false);
        when(jwtTokenProvider.getUserType("nt")).thenReturn(null);

        handler.afterConnectionEstablished(session);

        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    @DisplayName("tenantId/userId 缺失 → 拒绝")
    void rejectMissingIds() throws IOException {
        WebSocketSession session = mockSession("ws://host/ws/alerts?token=nid");
        when(jwtTokenProvider.validateToken("nid")).thenReturn(true);
        when(jwtTokenProvider.isAccessToken("nid")).thenReturn(true);
        when(blacklistService.isBlacklisted("nid")).thenReturn(false);
        when(jwtTokenProvider.getUserType("nid")).thenReturn("admin");
        when(jwtTokenProvider.getTenantId("nid")).thenReturn(null);

        handler.afterConnectionEstablished(session);

        verify(session).close(CloseStatus.POLICY_VIOLATION);
        assertEquals(0, handler.getOnlineCount(tenantId));
    }

    // ===== 连接认证：通过路径 =====

    @Test
    @DisplayName("教师 token → 注册成功，attributes 写入 tenantId/userId")
    void teacherConnectSuccess() {
        WebSocketSession session = mockSession("ws://host/ws/alerts?foo=1&token=tk&bar=2");
        stubValidTeacherToken("tk");

        handler.afterConnectionEstablished(session);

        assertEquals(tenantId, session.getAttributes().get("tenantId"));
        assertEquals(userId, session.getAttributes().get("userId"));
        assertEquals(1, handler.getOnlineCount(tenantId));
    }

    @Test
    @DisplayName("psych_teacher/class_teacher/admin 角色均可接入")
    void allAllowedRoles() {
        for (String role : new String[]{"psych_teacher", "class_teacher", "admin"}) {
            AlertWebSocketHandler h = new AlertWebSocketHandler(jwtTokenProvider, blacklistService, new ObjectMapper());
            WebSocketSession session = mockSession("ws://host/ws/alerts?token=" + role);
            when(jwtTokenProvider.validateToken(role)).thenReturn(true);
            when(jwtTokenProvider.isAccessToken(role)).thenReturn(true);
            when(blacklistService.isBlacklisted(role)).thenReturn(false);
            when(jwtTokenProvider.getUserType(role)).thenReturn(role);
            when(jwtTokenProvider.getTenantId(role)).thenReturn(tenantId);
            when(jwtTokenProvider.getUserId(role)).thenReturn(userId);

            h.afterConnectionEstablished(session);
            assertEquals(1, h.getOnlineCount(tenantId));
        }
    }

    @Test
    @DisplayName("同租户多 session 在线计数累加")
    void multipleSessionsSameTenant() {
        WebSocketSession s1 = mockSession("ws://host/ws/alerts?token=tk");
        WebSocketSession s2 = mockSession("ws://host/ws/alerts?token=tk");
        stubValidTeacherToken("tk");

        handler.afterConnectionEstablished(s1);
        handler.afterConnectionEstablished(s2);

        assertEquals(2, handler.getOnlineCount(tenantId));
    }

    // ===== 断连清理 =====

    @Test
    @DisplayName("断连后 session 移除，租户空则整组清理")
    void connectionClosedCleanup() {
        WebSocketSession session = mockSession("ws://host/ws/alerts?token=tk");
        stubValidTeacherToken("tk");
        handler.afterConnectionEstablished(session);
        assertEquals(1, handler.getOnlineCount(tenantId));

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        assertEquals(0, handler.getOnlineCount(tenantId));
    }

    @Test
    @DisplayName("未认证 session 断连（无 tenantId attribute）不抛异常")
    void closedUnauthenticatedSession() {
        WebSocketSession session = mockSession("ws://host/ws/alerts");
        assertDoesNotThrow(() -> handler.afterConnectionClosed(session, CloseStatus.NORMAL));
    }

    // ===== 心跳 =====

    @Test
    @DisplayName("ping → pong 心跳响应")
    void pingPong() throws IOException {
        WebSocketSession session = mockSession("ws://host/ws/alerts?token=tk");
        handler.handleTextMessage(session, new TextMessage("ping"));
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        assertEquals("pong", captor.getValue().getPayload());
    }

    @Test
    @DisplayName("非 ping 消息忽略；心跳发送 IOException 静默吞掉")
    void nonPingIgnoredAndHeartbeatIoErrorSwallowed() throws IOException {
        WebSocketSession session = mockSession("ws://host/ws/alerts?token=tk");
        handler.handleTextMessage(session, new TextMessage("hello"));
        verify(session, never()).sendMessage(any());

        doThrow(new IOException("broken")).when(session).sendMessage(any(TextMessage.class));
        assertDoesNotThrow(() -> handler.handleTextMessage(session, new TextMessage("ping")));
    }

    // ===== 预警推送 =====

    @Test
    @DisplayName("pushAlert：无在线 session 静默返回；有 session 则推送 JSON")
    void pushAlert() throws IOException {
        assertEquals(0, handler.getOnlineCount(tenantId));
        handler.pushAlert(tenantId, Map.of("type", "alert.new")); // 无人在线不抛异常

        WebSocketSession session = mockSession("ws://host/ws/alerts?token=tk");
        stubValidTeacherToken("tk");
        handler.afterConnectionEstablished(session);

        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "alert.new");
        payload.put("alertId", "a-1");
        handler.pushAlert(tenantId, payload);

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        String json = captor.getValue().getPayload();
        assertTrue(json.contains("alert.new"));
        assertTrue(json.contains("a-1"));
    }

    @Test
    @DisplayName("pushAlert：已关闭 session 被移除")
    void pushAlertRemovesClosedSession() {
        WebSocketSession session = mockSession("ws://host/ws/alerts?token=tk");
        stubValidTeacherToken("tk");
        handler.afterConnectionEstablished(session);
        when(session.isOpen()).thenReturn(false);

        handler.pushAlert(tenantId, Map.of("type", "alert.new"));
        assertEquals(0, handler.getOnlineCount(tenantId));
    }

    @Test
    @DisplayName("pushAlert：发送 IOException 的 session 被移除")
    void pushAlertRemovesBrokenSession() throws IOException {
        WebSocketSession session = mockSession("ws://host/ws/alerts?token=tk");
        stubValidTeacherToken("tk");
        handler.afterConnectionEstablished(session);
        doThrow(new IOException("broken pipe")).when(session).sendMessage(any(TextMessage.class));

        handler.pushAlert(tenantId, Map.of("type", "alert.new"));
        assertEquals(0, handler.getOnlineCount(tenantId));
    }

    @Test
    @DisplayName("pushAlert：序列化失败不推送不抛异常")
    void pushAlertSerializationFailure() {
        WebSocketSession session = mockSession("ws://host/ws/alerts?token=tk");
        stubValidTeacherToken("tk");
        handler.afterConnectionEstablished(session);

        // 自引用对象 Jackson 默认配置序列化失败
        Map<String, Object> cyclic = new HashMap<>();
        cyclic.put("self", cyclic);

        assertDoesNotThrow(() -> handler.pushAlert(tenantId, cyclic));
        assertEquals(1, handler.getOnlineCount(tenantId)); // session 不受影响
    }

    @Test
    @DisplayName("getOnlineCount：未知租户返回 0")
    void onlineCountUnknownTenant() {
        assertEquals(0, handler.getOnlineCount(UUID.randomUUID()));
    }

    // ===== AlertPushListener（事件→WebSocket 推送桥接） =====

    private RiskAlertPushEvent sampleEvent(UUID sessionId) {
        return new RiskAlertPushEvent(tenantId, UUID.randomUUID(), UUID.randomUUID(), sessionId,
                "self_harm", 4, "高危预警", "学生表达了自伤想法", Instant.parse("2026-07-28T08:00:00Z"));
    }

    @Test
    @DisplayName("listener：无在线教师时不推送")
    void listenerSkipsWhenNoTeacherOnline() {
        AlertPushListener listener = new AlertPushListener(handler);
        AlertWebSocketHandler spy = spy(handler);
        listener = new AlertPushListener(spy);

        listener.onRiskAlert(sampleEvent(UUID.randomUUID()));

        verify(spy, never()).pushAlert(any(), any());
    }

    @Test
    @DisplayName("listener：在线时推送完整 payload（含 sessionId）")
    void listenerPushesFullPayload() throws IOException {
        WebSocketSession session = mockSession("ws://host/ws/alerts?token=tk");
        stubValidTeacherToken("tk");
        handler.afterConnectionEstablished(session);

        UUID sessionId = UUID.randomUUID();
        new AlertPushListener(handler).onRiskAlert(sampleEvent(sessionId));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        String json = captor.getValue().getPayload();
        assertTrue(json.contains("risk_alert"));
        assertTrue(json.contains("self_harm"));
        assertTrue(json.contains("高危预警"));
        assertTrue(json.contains(sessionId.toString()));
        assertTrue(json.contains("2026-07-28T08:00:00Z"));
    }

    @Test
    @DisplayName("listener：sessionId 为 null 时 payload 省略该字段")
    void listenerOmitsNullSessionId() throws IOException {
        WebSocketSession session = mockSession("ws://host/ws/alerts?token=tk");
        stubValidTeacherToken("tk");
        handler.afterConnectionEstablished(session);

        new AlertPushListener(handler).onRiskAlert(sampleEvent(null));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        assertFalse(captor.getValue().getPayload().contains("sessionId"));
    }
}
