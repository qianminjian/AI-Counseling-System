package com.mindsafe.service.auth;

import com.mindsafe.common.tenant.TenantContextHolder;
import com.mindsafe.domain.entity.Tenant;
import com.mindsafe.domain.mapper.TenantMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 租户准入守卫（SEC-004）
 * <p>
 * 登录链路强制校验租户状态：仅 active 租户的用户可登录。
 * suspended/archived 租户的用户一律拒绝——"暂停租户 = 禁止使用"承诺兑现点。
 * <p>
 * 登录处于前置认证链路（无 JWT），租户查询需显式声明系统作用域。
 */
@Service
public class TenantAccessGuard {

    private static final Logger log = LoggerFactory.getLogger(TenantAccessGuard.class);

    private final TenantMapper tenantMapper;

    public TenantAccessGuard(TenantMapper tenantMapper) {
        this.tenantMapper = tenantMapper;
    }

    /**
     * 校验租户是否允许登录（仅 active 放行）。
     * 查询失败按拒绝处理（fail-close，认证场景不允许 fail-open）。
     */
    public boolean isLoginAllowed(UUID tenantId) {
        if (tenantId == null) return false;
        try {
            Tenant tenant = TenantContextHolder.callAsSystem(() -> tenantMapper.selectById(tenantId));
            if (tenant == null) {
                log.warn("登录拒绝：租户不存在, tenantId={}", tenantId);
                return false;
            }
            if (!Tenant.STATUS_ACTIVE.equals(tenant.getStatus())) {
                log.warn("登录拒绝：租户状态不可用, tenantId={}, status={}", tenantId, tenant.getStatus());
                return false;
            }
            return true;
        } catch (Exception e) {
            log.error("登录拒绝：租户状态查询失败（fail-close）, tenantId={}", tenantId, e);
            return false;
        }
    }
}
