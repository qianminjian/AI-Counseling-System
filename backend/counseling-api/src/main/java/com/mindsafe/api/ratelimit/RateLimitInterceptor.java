package com.mindsafe.api.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
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

    /** AUDIT-DEEP-011：公开端点 IP 限流统一宽松配额 300/min（防日志刷爆/滥用；声纹验证另有 controller 层 SEC-007 双限流） */
    private static final int IP_MAX_PER_MINUTE = 300;
    private static final Duration IP_WINDOW = Duration.ofMinutes(1);

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

        String action = resolveAction(request.getMethod(), request.getRequestURI());
        if (action == null) {
            return true; // 非限流路径
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean limited;
        if (auth != null && auth.getPrincipal() instanceof UUID userId) {
            limited = !rateLimiter.tryAcquire(userId, action);
        } else {
            // AUDIT-DEEP-011（P3-04）：公开端点（voiceprint/verify、device report/config）
            // 无认证用户 → 按 IP 限流（宽松配额防批量爆破/日志刷爆）
            // IP 取 X-Forwarded-For 末元素（nginx 追加 $remote_addr，不可伪造；首元素客户端可控）
            String forwarded = request.getHeader("X-Forwarded-For");
            String ip = request.getRemoteAddr();
            if (forwarded != null && !forwarded.isBlank()) {
                // P3-3（code-review）：从右往左跳空段取首个非空 IP（对齐 VoiceprintDomain.resolveClientIp）
                String[] parts = forwarded.split(",");
                for (int i = parts.length - 1; i >= 0; i--) {
                    String candidate = parts[i].trim();
                    if (!candidate.isEmpty()) {
                        ip = candidate;
                        break;
                    }
                }
            }
            limited = !rateLimiter.tryAcquire("ip:" + ip, action, IP_MAX_PER_MINUTE, IP_WINDOW);
        }
        if (limited) {
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
        // B-02：TTS 合成按用户限流（与 TtsController 文本长度上限双层防护）
        if (uri.contains("/tts/synthesize")) {
            return "tts_synthesize";
        }
        // AUDIT-DEEP-011（P3-04）：公开端点按 IP 限流（宽松配额）
        if (uri.contains("/voiceprint/verify")) {
            return "voiceprint_verify";
        }
        if (uri.contains("/device/report/")) {
            return "device_report";
        }
        if (uri.contains("/device/config/pull")) {
            return "device_config_pull";
        }
        return null;
    }
}
