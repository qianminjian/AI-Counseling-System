package com.mindsafe.api.controller;

import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.domain.entity.Tenant;
import com.mindsafe.service.audit.AuditLogService;
import com.mindsafe.service.tenant.TenantProvisioningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AdminTenantController 单元测试（P1 覆盖率冲刺：租户开通/暂停/恢复/健康/列表）
 */
class AdminTenantControllerTest {

    private TenantProvisioningService provisioningService;
    private AuditLogService auditLogService;
    private AdminTenantController controller;

    private final UUID adminTenantId = UUID.randomUUID();
    private final UUID adminUserId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        provisioningService = mock(TenantProvisioningService.class);
        auditLogService = mock(AuditLogService.class);
        controller = new AdminTenantController(provisioningService, auditLogService);
    }

    private Authentication adminAuth() {
        Authentication auth = mock(Authentication.class);
        when(auth.getDetails()).thenReturn(new TenantContext(adminTenantId, adminUserId, "super_admin"));
        return auth;
    }

    @Test
    @DisplayName("provision 缺必填字段 → error 响应（不触发开通）")
    void provision_missingFields() {
        ApiResponse<Map<String, Object>> resp = controller.provision(
                Map.of("tenantName", "第一小学", "adminPhone", "13800000000"), adminAuth());

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data()).containsKey("error");
        verify(provisioningService, never()).provisionTenant(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull());
    }

    @Test
    @DisplayName("provision 成功（显式 tempPassword 沿用）+ 审计")
    void provision_success_explicitPassword() {
        TenantProvisioningService.ProvisionResult result =
                new TenantProvisioningService.ProvisionResult(tenantId, UUID.randomUUID(), UUID.randomUUID());
        when(provisioningService.provisionTenant("SCH001", "第一小学", "13800000000", "张校长", "MyP@ss123"))
                .thenReturn(result);

        ApiResponse<Map<String, Object>> resp = controller.provision(
                Map.of("tenantCode", "SCH001", "tenantName", "第一小学",
                        "adminPhone", "13800000000", "adminName", "张校长", "tempPassword", "MyP@ss123"),
                adminAuth());

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data().get("tenantId")).isEqualTo(tenantId);
        assertThat((String) resp.data().get("message")).contains("MyP@ss123");
        verify(auditLogService).log(adminTenantId, adminUserId, "TENANT_PROVISION", "tenant", tenantId,
                "code=SCH001, name=第一小学");
    }

    @Test
    @DisplayName("provision 空 tempPassword → 生成 14 位强随机密码")
    void provision_success_generatedPassword() {
        TenantProvisioningService.ProvisionResult result =
                new TenantProvisioningService.ProvisionResult(tenantId, UUID.randomUUID(), UUID.randomUUID());
        when(provisioningService.provisionTenant(org.mockito.ArgumentMatchers.eq("SCH002"),
                org.mockito.ArgumentMatchers.eq("第二小学"), org.mockito.ArgumentMatchers.eq("13900000000"),
                org.mockito.ArgumentMatchers.eq("李校长"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(result);

        ApiResponse<Map<String, Object>> resp = controller.provision(
                Map.of("tenantCode", "SCH002", "tenantName", "第二小学",
                        "adminPhone", "13900000000", "adminName", "李校长", "tempPassword", "  "),
                adminAuth());

        assertThat(resp.code()).isEqualTo(0);
        String message = (String) resp.data().get("message");
        // 随机密码长度 14 + 不含易混淆字符
        String generated = message.substring(message.indexOf(": ") + 2);
        assertThat(generated).hasSize(14);
        assertThat(generated).doesNotContain("0", "O", "1", "l", "I");
    }

    @Test
    @DisplayName("suspend 带 reason + 审计")
    void suspend_withReason() {
        ApiResponse<Void> resp = controller.suspend(tenantId, Map.of("reason", "欠费"), adminAuth());

        assertThat(resp.code()).isEqualTo(0);
        verify(provisioningService).suspendTenant(tenantId, "欠费");
        verify(auditLogService).log(adminTenantId, adminUserId, "TENANT_SUSPEND", "tenant", tenantId, "欠费");
    }

    @Test
    @DisplayName("suspend 无请求体 → reason=null")
    void suspend_noBody() {
        controller.suspend(tenantId, null, adminAuth());

        verify(provisioningService).suspendTenant(tenantId, null);
        verify(auditLogService).log(adminTenantId, adminUserId, "TENANT_SUSPEND", "tenant", tenantId, null);
    }

    @Test
    @DisplayName("resume + 审计")
    void resume() {
        ApiResponse<Void> resp = controller.resume(tenantId, adminAuth());

        assertThat(resp.code()).isEqualTo(0);
        verify(provisioningService).resumeTenant(tenantId);
        verify(auditLogService).log(adminTenantId, adminUserId, "TENANT_RESUME", "tenant", tenantId, null);
    }

    @Test
    @DisplayName("health 透传健康检查结果")
    void health() {
        Map<String, Object> healthMap = Map.of("status", "UP", "db", true);
        when(provisioningService.healthCheck(tenantId)).thenReturn(healthMap);

        ApiResponse<Map<String, Object>> resp = controller.health(tenantId);

        assertThat(resp.data()).isEqualTo(healthMap);
    }

    @Test
    @DisplayName("list 映射租户字段")
    void list() {
        Tenant t = new Tenant();
        t.setTenantId(tenantId);
        t.setTenantCode("SCH001");
        t.setTenantName("第一小学");
        t.setStatus("active");
        t.setDataRegion("cn-east-1");
        t.setCreatedAt(Instant.now());
        when(provisioningService.listTenants()).thenReturn(List.of(t));

        ApiResponse<List<Map<String, Object>>> resp = controller.list();

        assertThat(resp.data()).hasSize(1);
        Map<String, Object> m = resp.data().get(0);
        assertThat(m.get("tenantId")).isEqualTo(tenantId);
        assertThat(m.get("tenantCode")).isEqualTo("SCH001");
        assertThat(m.get("tenantName")).isEqualTo("第一小学");
        assertThat(m.get("status")).isEqualTo("active");
        assertThat(m.get("dataRegion")).isEqualTo("cn-east-1");
        assertThat(m.get("createdAt")).isNotNull();
    }

    @Test
    @DisplayName("list 无租户 → 空列表")
    void list_empty() {
        when(provisioningService.listTenants()).thenReturn(List.of());

        ApiResponse<List<Map<String, Object>>> resp = controller.list();

        assertThat(resp.data()).isEmpty();
    }

    private static String anyOrNull() {
        return null;
    }
}
