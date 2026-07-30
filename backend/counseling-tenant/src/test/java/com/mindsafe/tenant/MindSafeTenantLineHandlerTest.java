package com.mindsafe.tenant;

import com.mindsafe.common.tenant.TenantContextHolder;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P-02 租户行隔离处理器 单元测试（策略 B）
 */
class MindSafeTenantLineHandlerTest {

    private final MindSafeTenantLineHandler handler = new MindSafeTenantLineHandler();

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("无租户上下文 → 所有表跳过注入（前置认证/@Scheduled/迁移）")
    void noContext_ignoresAllTables() {
        assertTrue(handler.ignoreTable("users"));
        assertTrue(handler.ignoreTable("tenant_template.risk_events"));
    }

    @Test
    @DisplayName("有上下文 → 业务表注入条件（不忽略）")
    void withContext_scopesBusinessTable() {
        TenantContextHolder.set(UUID.randomUUID());
        assertFalse(handler.ignoreTable("users"));
        assertFalse(handler.ignoreTable("tenant_template.risk_events"));
        assertFalse(handler.ignoreTable("\"tenant_template\".\"counseling_sessions\""));
    }

    @Test
    @DisplayName("有上下文 → 公共标识表 tenants 恒定忽略")
    void withContext_ignoresTenantsTable() {
        TenantContextHolder.set(UUID.randomUUID());
        assertTrue(handler.ignoreTable("tenants"));
        assertTrue(handler.ignoreTable("public.tenants"));
    }

    @Test
    @DisplayName("有上下文 → getTenantId 返回当前租户 UUID 字面量")
    void withContext_getTenantIdReturnsCurrent() {
        UUID tenantId = UUID.randomUUID();
        TenantContextHolder.set(tenantId);

        Expression expr = handler.getTenantId();

        assertInstanceOf(StringValue.class, expr);
        assertEquals(tenantId.toString(), ((StringValue) expr).getValue());
    }

    @Test
    @DisplayName("无上下文 → getTenantId 抛异常（理论不可达的防御）")
    void noContext_getTenantIdThrows() {
        assertThrows(IllegalStateException.class, handler::getTenantId);
    }

    @Test
    @DisplayName("租户列名固定为 tenant_id")
    void tenantIdColumn() {
        assertEquals("tenant_id", handler.getTenantIdColumn());
    }
}
