package com.mindsafe.api.websocket;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 注册配置
 * 端点：/ws/alerts（教师端预警实时推送）
 * 认证：AlertAuthHandshakeInterceptor 从 Sec-WebSocket-Protocol（auth.&lt;jwt&gt; 项）提取 JWT，
 * 不进 query string（P1-FE-4）；Origin 白名单复用 CORS 配置（mindsafe.cors.allowed-origins），收敛跨站握手攻击面
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final AlertWebSocketHandler alertWebSocketHandler;
    private final AlertAuthHandshakeInterceptor alertAuthHandshakeInterceptor;

    @Value("${mindsafe.cors.allowed-origins:https://yun.gxjugu.com,http://localhost:5173,http://localhost:5174}")
    private String allowedOrigins;

    public WebSocketConfig(AlertWebSocketHandler alertWebSocketHandler,
                           AlertAuthHandshakeInterceptor alertAuthHandshakeInterceptor) {
        this.alertWebSocketHandler = alertWebSocketHandler;
        this.alertAuthHandshakeInterceptor = alertAuthHandshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(alertWebSocketHandler, "/ws/alerts")
                .addInterceptors(alertAuthHandshakeInterceptor)
                .setAllowedOrigins(allowedOrigins.split(","));
    }
}
