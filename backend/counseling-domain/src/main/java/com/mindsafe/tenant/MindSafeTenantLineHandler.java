package com.mindsafe.tenant;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.mindsafe.common.tenant.TenantContextHolder;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;

import java.util.Set;
import java.util.UUID;

/**
 * 租户行隔离处理器（P-02，稳健渐进策略 B）
 * <p>
 * 为已认证请求的 SQL 自动追加 {@code AND tenant_id = <当前租户>}，构成行级隔离的纵深防线，
 * 弥补此前「隔离仅靠开发者手工 {@code .eq(tenantId)}」的漏防风险（见 design/07 §11）。
 * <ul>
 *   <li><b>无租户上下文</b>（{@link TenantContextHolder#get()} 为 null）→ {@link #ignoreTable} 返回 true，
 *       跳过条件注入。覆盖前置认证流程（登录/注册）、{@code @Scheduled}、Flyway 迁移、{@code @Async} 线程。</li>
 *   <li><b>公共标识表</b> {@code tenants}（public schema，tenant_id 为主键身份而非归属列）→ 恒定忽略，
 *       否则平台级「列出所有租户」会被误注入条件。</li>
 *   <li>其余 20 张业务表均含 tenant_id 列，正常注入。</li>
 * </ul>
 * PG 中未定型字符串字面量会隐式转为 uuid 列类型，故用 {@link StringValue} 承载租户 UUID。
 */
public class MindSafeTenantLineHandler implements TenantLineHandler {

    /** 恒定忽略的表（公共标识表，无租户归属语义）。 */
    private static final Set<String> IGNORE_TABLES = Set.of("tenants");

    @Override
    public Expression getTenantId() {
        UUID tenantId = TenantContextHolder.get();
        if (tenantId == null) {
            // 理论不可达：ignoreTable 已在无上下文时返回 true 而不会走到取值
            throw new IllegalStateException("TenantLineHandler.getTenantId 在无租户上下文时被调用");
        }
        return new StringValue(tenantId.toString());
    }

    @Override
    public String getTenantIdColumn() {
        return "tenant_id";
    }

    @Override
    public boolean ignoreTable(String tableName) {
        // 策略 B：无上下文一律跳过注入，交由调用方显式 ID / 手工过滤兜底
        if (TenantContextHolder.get() == null) {
            return true;
        }
        return IGNORE_TABLES.contains(normalize(tableName));
    }

    /** 去除 schema 前缀与反引号/双引号，取小写简单表名。 */
    private String normalize(String tableName) {
        if (tableName == null) {
            return "";
        }
        String name = tableName.replace("`", "").replace("\"", "");
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            name = name.substring(dot + 1);
        }
        return name.trim().toLowerCase();
    }
}
