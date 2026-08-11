package com.mindsafe.api.controller;

import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.domain.entity.AuditLog;
import com.mindsafe.service.admin.AdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuditLogController 单元测试（doing/92 R-002：审计日志独立端点 /api/v1/admin/audit-logs，
 * 全局资源从 invite-codes 子资源迁出）。
 */
@DisplayName("审计日志端点（R-002 独立资源）")
class AuditLogControllerTest {

    private final AdminService adminService = mock(AdminService.class);
    private AuditLogController controller;

    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        controller = new AuditLogController(adminService);
    }

    private Authentication adminAuth() {
        Authentication auth = mock(Authentication.class);
        when(auth.getDetails()).thenReturn(new TenantContext(tenantId, UUID.randomUUID(), "admin"));
        return auth;
    }

    @Test
    @DisplayName("getAuditLogs：从 details 取租户上下文，参数透传 AdminService")
    void getAuditLogs_passesTenantAndParams() {
        AuditLog log1 = new AuditLog();
        when(adminService.getAuditLogs(tenantId, "CONSENT_WITHDRAW", 200))
                .thenReturn(List.of(log1));

        ApiResponse<List<AuditLog>> resp = controller.getAuditLogs(adminAuth(), "CONSENT_WITHDRAW", 200);

        assertThat(resp.data()).containsExactly(log1);
        verify(adminService).getAuditLogs(eq(tenantId), eq("CONSENT_WITHDRAW"), eq(200));
    }

    @Test
    @DisplayName("getAuditLogs 无 action → null 透传（全量日志）")
    void getAuditLogs_nullActionPassedThrough() {
        controller.getAuditLogs(adminAuth(), null, 50);

        verify(adminService).getAuditLogs(eq(tenantId), eq(null), eq(50));
    }

    @Test
    @DisplayName("认证缺失/无 details → UNAUTHORIZED")
    void getAuditLogs_missingAuthRejected() {
        Authentication bare = mock(Authentication.class);
        when(bare.getDetails()).thenReturn(null);

        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> controller.getAuditLogs(bare, null, 200)))
                .isExactlyInstanceOf(com.mindsafe.common.exception.BizException.class)
                .extracting("code")
                .isEqualTo(com.mindsafe.common.dto.ErrorCode.UNAUTHORIZED.code());
    }
}
