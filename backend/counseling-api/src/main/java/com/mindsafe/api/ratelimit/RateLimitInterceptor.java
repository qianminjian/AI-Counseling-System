package com.mindsafe.api.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.UUID;

/**
 * 全局 API 限流拦截器
 * <p>
 * 对 /api/v1/chat/ 路径下的消息发送与会话创建接口进行限流：
 * - chat_message / create_session：每用户 30 次/分钟（配额由 RateLimiter 统一控制）
 * 会话轮次另有 12 轮/次上限（ConversationState.maxTurns）
 * <p>
 * 注册方式：WebMvcConfigurer.addInterceptors
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    private final RateLimiter rateLimiter;

    public RateLimitInterceptor(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 仅对 POST 消息接口限流
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UUID userId)) {
            return true; // 未认证请求由 Security 层拦截
        }

        String action = resolveAction(request.getMethod(), request.getRequestURI());
        if (action == null) {
            return true; // 非限流路径
        }

        if (!rateLimiter.tryAcquire(userId, action)) {
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"code\":429,\"message\":\"操作太频繁了，请稍等一下再试哦 🌈\",\"data\":null}"
            );
            return false;
        }

        return true;
    }

    /** 根据 HTTP 方法与 URI 确定限流动作（null = 不限流） */
    private String resolveAction(String method, String uri) {
        if (uri.contains("/chat/sessions/") && uri.contains("/messages")) {
            return "chat_message";
        }
        // AUDIT-P0-2：原实现误将路径 uri 与字面量 "POST" 比较（恒 false），
        // create_session 限流从未生效，攻击者可无限创建会话烧 LLM 配额。
        // 修复：改判 request.getMethod()。
        if (uri.contains("/chat/sessions") && "POST".equalsIgnoreCase(method)) {
            return "create_session";
        }
        return null;
    }
}
