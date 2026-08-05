package com.mindsafe.api.websocket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AlertAuthHandshakeInterceptor 单元测试（P1-FE-4：JWT 不进 query string）
 * <p>
 * 契约：从 Sec-WebSocket-Protocol 头中提取 auth.<jwt> 项写入 attributes["wsToken"]；
 * 只搬运不校验（handler 是唯一认证点）；缺失时放行，由 handler 统一拒绝。
 */
class AlertAuthHandshakeInterceptorTest {

    private static final String SEC_WEBSOCKET_PROTOCOL = "Sec-WebSocket-Protocol";

    private AlertAuthHandshakeInterceptor interceptor;
    private Map<String, Object> attributes;

    @BeforeEach
    void setUp() {
        interceptor = new AlertAuthHandshakeInterceptor();
        attributes = new HashMap<>();
    }

    private ServerHttpRequest requestWithProtocols(String headerValue) {
        HttpHeaders headers = new HttpHeaders();
        if (headerValue != null) {
            headers.add(SEC_WEBSOCKET_PROTOCOL, headerValue);
        }
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getHeaders()).thenReturn(headers);
        return request;
    }

    @Test
    @DisplayName("存在 auth.<jwt> 项 → 提取 JWT 到 attributes，握手放行")
    void extractsTokenFromAuthProtocol() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.abc.def";
        ServerHttpRequest request = requestWithProtocols("alerts.v1, auth." + jwt);

        boolean proceed = interceptor.beforeHandshake(request, mock(ServerHttpResponse.class), null, attributes);

        assertTrue(proceed);
        assertEquals(jwt, attributes.get(AlertAuthHandshakeInterceptor.TOKEN_ATTR));
    }

    @Test
    @DisplayName("auth 项在首个位置 → 同样提取")
    void extractsTokenWhenFirst() {
        ServerHttpRequest request = requestWithProtocols("auth.tk-1, alerts.v1");

        interceptor.beforeHandshake(request, mock(ServerHttpResponse.class), null, attributes);

        assertEquals("tk-1", attributes.get(AlertAuthHandshakeInterceptor.TOKEN_ATTR));
    }

    @Test
    @DisplayName("无 auth 前缀项 → 不写 attributes，握手放行（由 handler 拒绝）")
    void noAuthProtocolLeavesAttributesEmpty() {
        ServerHttpRequest request = requestWithProtocols("alerts.v1");

        boolean proceed = interceptor.beforeHandshake(request, mock(ServerHttpResponse.class), null, attributes);

        assertTrue(proceed);
        assertFalse(attributes.containsKey(AlertAuthHandshakeInterceptor.TOKEN_ATTR));
    }

    @Test
    @DisplayName("无 Sec-WebSocket-Protocol 头 → 不写 attributes，握手放行")
    void noHeaderLeavesAttributesEmpty() {
        ServerHttpRequest request = requestWithProtocols(null);

        boolean proceed = interceptor.beforeHandshake(request, mock(ServerHttpResponse.class), null, attributes);

        assertTrue(proceed);
        assertTrue(attributes.isEmpty());
    }

    @Test
    @DisplayName("afterHandshake 空实现不抛异常")
    void afterHandshakeDoesNotThrow() {
        assertDoesNotThrow(() ->
                interceptor.afterHandshake(requestWithProtocols(null), mock(ServerHttpResponse.class), null, null));
    }
}
