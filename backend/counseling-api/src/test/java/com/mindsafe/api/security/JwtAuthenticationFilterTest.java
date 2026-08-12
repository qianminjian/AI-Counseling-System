package com.mindsafe.api.security;

import com.mindsafe.service.auth.TokenBlacklistService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JWT 认证过滤器单元测试（ADMIN-P0-02/03：平台 token 独立授权域 + 黑名单 + 业务 token 租户绑定）
 */
class JwtAuthenticationFilterTest {

    private JwtTokenProvider jwtTokenProvider;
    private TokenBlacklistService blacklistService;
    private JwtAuthenticationFilter filter;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        jwtTokenProvider = mock(JwtTokenProvider.class);
        blacklistService = mock(TokenBlacklistService.class);
        filter = new JwtAuthenticationFilter(jwtTokenProvider, blacklistService);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        filterChain = mock(FilterChain.class);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("平台 token：建立 ROLE_PLATFORM_<角色> 授权，不绑定租户")
    void platformTokenBuildsPlatformAuth() throws Exception {
        UUID adminId = UUID.randomUUID();
        when(request.getHeader("Authorization")).thenReturn("Bearer PLATFORM_abc");
        when(jwtTokenProvider.isPlatformToken("PLATFORM_abc")).thenReturn(true);
        when(jwtTokenProvider.getTokenId("PLATFORM_abc")).thenReturn("tid-1");
        when(blacklistService.isBlacklisted("tid-1")).thenReturn(false);
        when(jwtTokenProvider.getPlatformAdminId("PLATFORM_abc")).thenReturn(adminId);
        when(jwtTokenProvider.getPlatformRole("PLATFORM_abc")).thenReturn("ops_admin");

        filter.doFilter(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isEqualTo(adminId);
        assertThat(auth.getAuthorities()).extracting("authority")
                .containsExactly("ROLE_PLATFORM_OPS_ADMIN");
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("平台 token 已撤销（黑名单）→ 不建立认证")
    void blacklistedPlatformTokenNoAuth() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer PLATFORM_abc");
        when(jwtTokenProvider.isPlatformToken("PLATFORM_abc")).thenReturn(true);
        when(jwtTokenProvider.getTokenId("PLATFORM_abc")).thenReturn("tid-black");
        when(blacklistService.isBlacklisted("tid-black")).thenReturn(true);

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(jwtTokenProvider, never()).getPlatformAdminId(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("业务 token：建立 ROLE_<userType> 授权 + 绑定租户上下文")
    void businessTokenBuildsAuthWithTenant() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        when(request.getHeader("Authorization")).thenReturn("Bearer biz-token");
        when(jwtTokenProvider.isPlatformToken("biz-token")).thenReturn(false);
        // doing/92 R-017：filter 单次 parse——mock parseOnce 返回快照 record
        when(jwtTokenProvider.parseOnce("biz-token"))
                .thenReturn(new JwtTokenProvider.ParsedToken("tid-2", userId, "STUDENT", tenantId, TokenType.ACCESS));
        when(blacklistService.isBlacklisted("tid-2")).thenReturn(false);

        filter.doFilter(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_STUDENT");
        assertThat(auth.getDetails()).isInstanceOf(JwtAuthenticationFilter.TenantContext.class);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("无 Authorization 头 → 直通不建认证")
    void noTokenPassesThrough() throws Exception {
        when(request.getHeader("Authorization")).thenReturn(null);

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("BUG-A-TOKEN-01 回归：过期/签名无效 token 解析失败 → 不建认证、不抛异常（安全链统一 401）")
    void expiredTokenParsingFailureNoAuth() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer expired-token");
        when(jwtTokenProvider.isPlatformToken("expired-token")).thenReturn(false);
        when(jwtTokenProvider.parseOnce("expired-token"))
                .thenThrow(new io.jsonwebtoken.ExpiredJwtException(null, null, "expired"));

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }
}
