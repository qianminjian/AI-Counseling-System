package com.mindsafe.api.filter;

import com.mindsafe.common.tenant.TenantContextHolder;
import com.mindsafe.domain.entity.Tenant;
import com.mindsafe.domain.mapper.TenantMapper;
import com.mindsafe.service.billing.EntitlementChecker;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 权益过滤器（BILL-001，design/38 §4.2）
 * <p>
 * 拦截非豁免路径，检查租户订阅计划的功能权益与配额。
 * <ul>
 *   <li>豁免路径（预警/SOS/危机）直接放行，不可配置覆盖</li>
 *   <li>功能权益不满足 → 403</li>
 *   <li>配额超限 → 429（需订阅服务提供当前用量后激活）</li>
 * </ul>
 * Plan 解析策略：从 TenantContextHolder 获取 tenantId → 查 tenants 表 →
 * 根据 status 映射 Plan（active→STANDARD, trial→TRIAL, suspended/其他→TRIAL）。
 * 无上下文（未认证路径）默认 STANDARD 放行。
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class EntitlementFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(EntitlementFilter.class);

    private final EntitlementChecker entitlementChecker;
    private final TenantMapper tenantMapper;

    public EntitlementFilter(EntitlementChecker entitlementChecker, TenantMapper tenantMapper) {
        this.entitlementChecker = entitlementChecker;
        this.tenantMapper = tenantMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();

        // 豁免路径直接放行（S0-S1 预警/SOS/危机全链路，硬编码不可覆盖）
        if (entitlementChecker.isExempt(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 路径→功能权益映射
        String feature = mapPathToFeature(path);
        if (feature == null) {
            // 非受控路径，直接放行
            filterChain.doFilter(request, response);
            return;
        }

        EntitlementChecker.Plan plan = resolveTenantPlan(request);

        EntitlementChecker.CheckResult result = entitlementChecker.checkFeature(plan, feature, path);
        if (!result.allowed()) {
            log.warn("权益拦截: path={}, feature={}, status={}, code={}", path, feature, result.httpStatus(), result.code());
            response.setStatus(result.httpStatus());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"code\":" + result.code() + ",\"message\":\"" + result.message() + "\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 路径→功能权益映射（仅映射受控路径，其余返回 null 放行）。
     */
    private String mapPathToFeature(String path) {
        if (path.startsWith("/api/v1/chat") || path.startsWith("/api/v1/conversations")) {
            return EntitlementChecker.FEAT_AI_CHAT;
        }
        if (path.startsWith("/api/v1/tts")) {
            return EntitlementChecker.FEAT_TTS;
        }
        if (path.startsWith("/api/v1/voice")) {
            return EntitlementChecker.FEAT_VOICE_INPUT;
        }
        if (path.startsWith("/api/v1/parent")) {
            return EntitlementChecker.FEAT_PARENT_H5;
        }
        if (path.startsWith("/api/v1/admin/export")) {
            return EntitlementChecker.FEAT_EXPORT;
        }
        if (path.startsWith("/api/v1/admin/dashboard")) {
            return EntitlementChecker.FEAT_DATA_DASHBOARD;
        }
        return null; // 非受控路径
    }

    /**
     * 解析租户订阅计划：从 TenantContextHolder 取 tenantId → 查 tenants 表 → 映射 Plan。
     * <ul>
     *   <li>active → STANDARD（当前无独立 plan 字段，active 租户享有标准版权益）</li>
     *   <li>trial → TRIAL</li>
     *   <li>suspended / 其他 / 查不到 → TRIAL（最严格权益）</li>
     *   <li>无租户上下文（未认证路径）→ STANDARD 放行（受 SecurityConfig 保护）</li>
     * </ul>
     */
    private EntitlementChecker.Plan resolveTenantPlan(HttpServletRequest request) {
        UUID tenantId = TenantContextHolder.get();
        if (tenantId == null) {
            // 未认证路径（登录/注册等），由 SecurityConfig 控制访问
            return EntitlementChecker.Plan.STANDARD;
        }
        try {
            Tenant tenant = tenantMapper.selectById(tenantId);
            if (tenant == null) {
                log.warn("租户不存在: tenantId={}", tenantId);
                return EntitlementChecker.Plan.TRIAL;
            }
            return mapStatusToPlan(tenant.getStatus());
        } catch (Exception e) {
            // 查库异常降级为 STANDARD，避免阻断核心业务
            log.error("解析租户计划异常，降级为 STANDARD: tenantId={}", tenantId, e);
            return EntitlementChecker.Plan.STANDARD;
        }
    }

    private static EntitlementChecker.Plan mapStatusToPlan(String status) {
        if (status == null) {
            return EntitlementChecker.Plan.TRIAL;
        }
        return switch (status) {
            case "active" -> EntitlementChecker.Plan.STANDARD;
            case "trial" -> EntitlementChecker.Plan.TRIAL;
            default -> EntitlementChecker.Plan.TRIAL; // suspended/inactive/未知 → 最严格
        };
    }
}
