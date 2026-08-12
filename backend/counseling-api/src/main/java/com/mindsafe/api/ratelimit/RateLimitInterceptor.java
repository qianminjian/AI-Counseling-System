package com.mindsafe.api.ratelimit;

import com.mindsafe.api.config.ErrorResponseWriter;
import com.mindsafe.api.config.RouteCatalog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.Optional;
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

        // F3：限流动作收敛 RouteCatalog 注册表（行为与原 resolveAction 逐条等价）
        Optional<String> actionOpt = RouteCatalog.rateLimitAction(request.getMethod(), request.getRequestURI());
        if (actionOpt.isEmpty()) {
            return true; // 非限流路径
        }
        String action = actionOpt.get();

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
            // F6：统一 ApiResponse 序列化出口（原手拼 {code,message,data} 缺 timestamp，与契约对齐）
            ErrorResponseWriter.write(response, 429, 429, "操作太频繁了，请稍等一下再试哦 🌈");
            return false;
        }

        return true;
    }
}
