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
 * 对 /api/v1/chat/ 路径下的消息发送接口进行限流：
 * - 每用户 30 次/分钟
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

        String action = resolveAction(request.getRequestURI());
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

    /** 根据 URI 确定限流动作（null = 不限流） */
    private String resolveAction(String uri) {
        if (uri.contains("/chat/sessions/") && uri.contains("/messages")) {
            return "chat_message";
        }
        if (uri.contains("/chat/sessions") && "POST".equalsIgnoreCase(uri)) {
            return "create_session";
        }
        return null;
    }
}
