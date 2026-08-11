package com.mindsafe.api.security;

import com.mindsafe.common.tenant.TenantContextHolder;
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

        if (token != null && jwtTokenProvider.isPlatformToken(token)) {
            // 平台 token（ADMIN-P0-02/03，R-8）：独立授权域 ROLE_PLATFORM_<角色>，
            // 不绑定租户上下文（平台操作无租户归属）；已撤销（黑名单）token 不建立认证（M1）
            if (!blacklistService.isBlacklisted(jwtTokenProvider.getTokenId(token))) {
                UUID adminId = jwtTokenProvider.getPlatformAdminId(token);
                String role = jwtTokenProvider.getPlatformRole(token);
                var authorities = List.of(new SimpleGrantedAuthority("ROLE_PLATFORM_" + role.toUpperCase()));
                var auth = new UsernamePasswordAuthenticationToken(adminId, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        } else if (token != null) {
            // doing/92 R-017：单次 parse（原 6 次 parse/请求：validate+isAccess+getTokenId+getUserId+getUserType+getTenantId）
            var parsed = jwtTokenProvider.parseOnce(token);
            if (!"access".equals(parsed.tokenType())
                    || blacklistService.isBlacklisted(parsed.tokenId())) {
                // 非 access 类型 或 已撤销（黑名单）token → 不建立认证
                return;
            }

            UUID userId = parsed.userId();
            String userType = parsed.userType();
            UUID tenantId = parsed.tenantId();

            var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + userType.toUpperCase()));
            var auth = new UsernamePasswordAuthenticationToken(userId, null, authorities);
            auth.setDetails(new TenantContext(tenantId, userId, userType));
            SecurityContextHolder.getContext().setAuthentication(auth);
            // 绑定租户到持久层拦截器（P-02 行隔离纵深防线）
            TenantContextHolder.set(tenantId);
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            // 请求结束务必清除，防止线程池复用时串租户
            TenantContextHolder.clear();
        }
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
