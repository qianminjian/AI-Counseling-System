package com.mindsafe.api.websocket;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.List;
import java.util.Map;

/**
 * WebSocket 握手认证头提取（P1-FE-4：JWT 不再进 query string）
 * <p>
 * 前端连接 /ws/alerts 时通过 Sec-WebSocket-Protocol 携带 <code>auth.&lt;jwt&gt;</code> 子协议项，
 * JWT 在握手请求头中传输：不进 nginx access log、不落浏览器历史。
 * 本拦截器只负责提取放入 attributes，认证校验统一由 AlertWebSocketHandler 完成（单一认证点）。
 */
public class AlertAuthHandshakeInterceptor implements HandshakeInterceptor {

    private static final String SEC_WEBSOCKET_PROTOCOL = "Sec-WebSocket-Protocol";

    /** attributes 中存放 JWT 的 key（AlertWebSocketHandler 认证时读取） */
    public static final String TOKEN_ATTR = "wsToken";

    private static final String AUTH_PROTOCOL_PREFIX = "auth.";

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        List<String> headerValues = request.getHeaders().get(SEC_WEBSOCKET_PROTOCOL);
        if (headerValues != null) {
            // 兼容不同容器解析差异：值可能整体含逗号，统一按逗号拆分后去空格匹配
            for (String raw : headerValues) {
                for (String protocol : raw.split(",")) {
                    String trimmed = protocol.trim();
                    if (trimmed.startsWith(AUTH_PROTOCOL_PREFIX)) {
                        attributes.put(TOKEN_ATTR, trimmed.substring(AUTH_PROTOCOL_PREFIX.length()));
                        return true;
                    }
                }
            }
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 无收尾逻辑
    }
}
