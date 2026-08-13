package com.mindsafe.api.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RateLimitInterceptor 全局限流拦截器单测：非 POST/非限流路径放行、
 * 已认证用户限流、未认证 IP 限流（X-Forwarded-For 取末元素）、限流 429 写入。
 */
@ExtendWith(MockitoExtension.class)
class RateLimitInterceptorTest {

    @Mock private RateLimiter rateLimiter;
    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;

    private RateLimitInterceptor interceptor;

    @BeforeEach
    void setUp() throws Exception {
        interceptor = new RateLimitInterceptor(rateLimiter);
        // ErrorResponseWriter 需要写响应体（仅限流用例使用，lenient 防 UnnecessaryStubbing）
        lenient().when(response.getWriter()).thenReturn(new java.io.PrintWriter(new java.io.StringWriter()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("非 POST 请求直接放行")
    void preHandle_nonPost() throws Exception {
        when(request.getMethod()).thenReturn("GET");

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();

        verify(rateLimiter, never()).tryAcquire(org.mockito.ArgumentMatchers.any(), anyString());
    }

    @Test
    @DisplayName("POST 但非限流路径（如 /chat/sessions 无消息）放行")
    void preHandle_notLimitedPath() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/v1/other");

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    @Test
    @DisplayName("已认证用户：限流放行（principal 为 UUID）")
    void preHandle_authenticated_allowed() throws Exception {
        UUID userId = UUID.randomUUID();
        Authentication auth = new UsernamePasswordAuthenticationToken(userId, null);
        SecurityContextHolder.getContext().setAuthentication(auth);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/v1/chat/sessions");
        when(rateLimiter.tryAcquire(userId, "create_session")).thenReturn(true);

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    @Test
    @DisplayName("已认证用户：超限返回 false 并写 429")
    void preHandle_authenticated_limited() throws Exception {
        UUID userId = UUID.randomUUID();
        Authentication auth = new UsernamePasswordAuthenticationToken(userId, null);
        SecurityContextHolder.getContext().setAuthentication(auth);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/v1/chat/sessions");
        when(rateLimiter.tryAcquire(userId, "create_session")).thenReturn(false);

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        verify(response).setStatus(429);
    }

    @Test
    @DisplayName("未认证：按 IP 限流（X-Forwarded-For 取末元素防伪造）")
    void preHandle_unauthenticated_ip() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/v1/voiceprint/verify");
        when(request.getHeader("X-Forwarded-For")).thenReturn("1.1.1.1, 2.2.2.2");
        when(request.getRemoteAddr()).thenReturn("3.3.3.3");
        when(rateLimiter.tryAcquire(anyString(), anyString(), anyInt(), any())).thenReturn(true);

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();

        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(rateLimiter).tryAcquire(captor.capture(), anyString(), anyInt(), any());
        assertThat(captor.getValue()).isEqualTo("ip:2.2.2.2");
    }

    @Test
    @DisplayName("未认证：无 X-Forwarded-For 用 remoteAddr")
    void preHandle_unauthenticated_remoteAddr() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/v1/voiceprint/verify");
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("3.3.3.3");
        when(rateLimiter.tryAcquire(anyString(), anyString(), anyInt(), any())).thenReturn(false);

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        verify(response).setStatus(429);
    }

    @Test
    @DisplayName("X-Forwarded-For 空白段跳过：取右侧首个非空")
    void preHandle_forwardedBlankSkip() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/v1/voiceprint/verify");
        when(request.getHeader("X-Forwarded-For")).thenReturn("1.1.1.1, , 2.2.2.2,");
        when(request.getRemoteAddr()).thenReturn("3.3.3.3");
        when(rateLimiter.tryAcquire(anyString(), anyString(), anyInt(), any())).thenReturn(true);

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();

        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(rateLimiter).tryAcquire(captor.capture(), anyString(), anyInt(), any());
        assertThat(captor.getValue()).isEqualTo("ip:2.2.2.2");
    }
}
