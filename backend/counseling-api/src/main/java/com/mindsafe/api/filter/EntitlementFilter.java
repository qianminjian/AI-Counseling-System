package com.mindsafe.api.filter;

import com.mindsafe.api.config.ErrorResponseWriter;
import com.mindsafe.api.config.RouteCatalog;
import com.mindsafe.common.tenant.TenantContextHolder;
import com.mindsafe.service.billing.EntitlementChecker;
import com.mindsafe.service.billing.TenantPlanResolver;
import io.micrometer.core.instrument.MeterRegistry;
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
import java.util.Optional;

/**
 * 权益过滤器（BILL-001，design/38 §4.2）
 * <p>
 * 拦截非豁免路径，检查租户订阅计划的功能权益与配额。
 * <ul>
 *   <li>豁免路径（预警/SOS/危机）直接放行，不可配置覆盖</li>
 *   <li>功能权益不满足 → 403</li>
 *   <li>配额超限 → 429（需订阅服务提供当前用量后激活）</li>
 * </ul>
 * Plan 解析策略：从 TenantContextHolder 获取 tenantId → TenantPlanResolver 查 tenants 表 →
 * 根据 status 映射 Plan（active→STANDARD, trial→TRIAL, suspended/其他→TRIAL）。
 * 无上下文（未认证路径）默认 STANDARD 放行。
 * （B-13：计划解析下沉 service 层，filter 不再直连 TenantMapper）
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class EntitlementFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(EntitlementFilter.class);

    private final EntitlementChecker entitlementChecker;
    private final TenantPlanResolver tenantPlanResolver;
    private final MeterRegistry meterRegistry;

    public EntitlementFilter(EntitlementChecker entitlementChecker, TenantPlanResolver tenantPlanResolver, MeterRegistry meterRegistry) {
        this.entitlementChecker = entitlementChecker;
        this.tenantPlanResolver = tenantPlanResolver;
        this.meterRegistry = meterRegistry;
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

        // 路径→功能权益映射（F3：收敛 RouteCatalog 注册表，行为与原 mapPathToFeature 逐条等价）
        Optional<String> featureOpt = RouteCatalog.entitlementFeature(path);
        if (featureOpt.isEmpty()) {
            // 非受控路径，直接放行
            filterChain.doFilter(request, response);
            return;
        }
        String feature = featureOpt.get();

        EntitlementChecker.Plan plan = tenantPlanResolver.resolve(TenantContextHolder.get());

        EntitlementChecker.CheckResult result = entitlementChecker.checkFeature(plan, feature, path);
        if (!result.allowed()) {
            log.warn("权益拦截: path={}, feature={}, status={}, code={}", path, feature, result.httpStatus(), result.code());
            // F6：统一 ApiResponse 序列化出口（原手拼 {code,message} 缺 data/timestamp）
            ErrorResponseWriter.write(response, result.httpStatus(),
                    Integer.parseInt(result.code()), result.message());
            return;
        }

        filterChain.doFilter(request, response);
    }
}
