package com.mindsafe.api.ratelimit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RateLimitInterceptor 单元测试（AUDIT-P0-2 回归）
 * <p>
 * 背景：原 resolveAction 将路径 uri 与字面量 "POST" 比较（恒 false），
 * create_session 限流从未生效。本测试锁死两个动作的命中规则，
 * 防止再次出现"看起来有限流实际没有"的回归。
 */
class RateLimitInterceptorTest {

    private RateLimiter rateLimiter;
    private RateLimitInterceptor interceptor;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        rateLimiter = mock(RateLimiter.class);
        interceptor = new RateLimitInterceptor(rateLimiter);
        when(rateLimiter.tryAcquire(any(UUID.class), any(String.class))).thenReturn(true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("POST /chat/sessions 命中 create_session 限流（P0-2 回归）")
    void createSessionHitsRateLimit() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/chat/sessions");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(req, resp, new Object())).isTrue();

        verify(rateLimiter).tryAcquire(eq(userId), eq("create_session"));
    }

    @Test
    @DisplayName("POST /chat/sessions/{id}/messages 命中 chat_message 限流")
    void chatMessageHitsRateLimit() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(
                "POST", "/api/v1/chat/sessions/" + UUID.randomUUID() + "/messages");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(req, resp, new Object())).isTrue();

        verify(rateLimiter).tryAcquire(eq(userId), eq("chat_message"));
    }

    @Test
    @DisplayName("GET 请求不触发限流（仅 POST）")
    void getNotRateLimited() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/chat/sessions");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(req, resp, new Object())).isTrue();

        verify(rateLimiter, never()).tryAcquire(any(UUID.class), any(String.class));
    }

    @Test
    @DisplayName("非 chat 路径不触发限流")
    void nonChatPathNotRateLimited() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(req, resp, new Object())).isTrue();

        verify(rateLimiter, never()).tryAcquire(any(UUID.class), any(String.class));
    }

    @Test
    @DisplayName("限流超限返回 429 并阻止请求")
    void overLimitReturns429() throws Exception {
        when(rateLimiter.tryAcquire(any(UUID.class), any(String.class))).thenReturn(false);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/chat/sessions");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(req, resp, new Object())).isFalse();
        assertThat(resp.getStatus()).isEqualTo(429);
        assertThat(resp.getContentAsString()).contains("\"code\":429");
    }

    @Test
    @DisplayName("未认证请求由 Security 层处理，不触发限流")
    void unauthenticatedNotRateLimited() throws Exception {
        SecurityContextHolder.clearContext();
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/chat/sessions");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(req, resp, new Object())).isTrue();

        verify(rateLimiter, never()).tryAcquire(any(UUID.class), any(String.class));
    }
}
