package com.mindsafe.api.security;

import com.mindsafe.service.auth.TokenBlacklistService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * JWT 认证过滤器：提取并验证 Access Token（黑名单 + 类型检查）
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final TokenBlacklistService blacklistService;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider,
                                   TokenBlacklistService blacklistService) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.blacklistService = blacklistService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request);

        if (token != null
                && jwtTokenProvider.validateToken(token)
                && jwtTokenProvider.isAccessToken(token)
                && !blacklistService.isBlacklisted(token)) {

            UUID userId = jwtTokenProvider.getUserId(token);
            String userType = jwtTokenProvider.getUserType(token);
            UUID tenantId = jwtTokenProvider.getTenantId(token);

            var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + userType.toUpperCase()));
            var auth = new UsernamePasswordAuthenticationToken(userId, null, authorities);
            auth.setDetails(new TenantContext(tenantId, userId, userType));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }

        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }

    /** 租户上下文（附加到 Authentication.details） */
    public record TenantContext(UUID tenantId, UUID userId, String userType) {
    }
}
