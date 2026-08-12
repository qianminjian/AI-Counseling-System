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
            // 不绑定租户（平台操作无租户归属）；已撤销（黑名单）token 不建立认证（M1）
            if (!blacklistService.isBlacklisted(jwtTokenProvider.getTokenId(token))) {
                UUID adminId = jwtTokenProvider.getPlatformAdminId(token);
                String role = jwtTokenProvider.getPlatformRole(token);
                var authorities = List.of(new SimpleGrantedAuthority("ROLE_PLATFORM_" + role.toUpperCase()));
                var auth = new UsernamePasswordAuthenticationToken(adminId, null, authorities);
                // BUG-A-12-01/02/04、BUG-A-04-01 修复（2026-08-12，UI-TEST-015）：平台 token 也建立
                // TenantContext（tenantId=null，userId=adminId）——平台 controller 的 ctx.tenantId()/userId()
                // 不再 NPE；并标记系统作用域，使平台域跨租户 SQL（health/provision/列表）豁免租户行
                // 隔离 fail-fast（MindSafeTenantLineHandler），避免平台接口 500。
                auth.setDetails(new TenantContext(null, adminId, JwtTokenProvider.PLATFORM_USER_TYPE));
                SecurityContextHolder.getContext().setAuthentication(auth);
                TenantContextHolder.setSystemScope(true);
            }
        } else if (token != null) {
            // doing/92 R-017：单次 parse（原 6 次 parse/请求：validate+isAccess+getTokenId+getUserId+getUserType+getTenantId）
            // BUG-A-TOKEN-01（2026-08-12）保留：parseOnce 对过期/签名无效仍抛 JwtException
            // （doing/92 R-016 枚举化不改变该行为）——捕获后不建认证、放行由安全链统一 401，避免落兜底 500
            JwtTokenProvider.ParsedToken parsed;
            try {
                parsed = jwtTokenProvider.parseOnce(token);
            } catch (Exception e) {
                parsed = null;
            }
            // doing/92 R-016：tokenType 枚举化（未知类型 tokenType=null → 非 ACCESS 拒绝）
            if (parsed != null && parsed.tokenType() == TokenType.ACCESS
                    && !blacklistService.isBlacklisted(parsed.tokenId())) {
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
