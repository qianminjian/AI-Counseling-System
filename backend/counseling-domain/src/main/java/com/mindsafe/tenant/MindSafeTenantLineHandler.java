package com.mindsafe.tenant;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.mindsafe.common.tenant.TenantContextHolder;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;

import java.util.Set;
import java.util.UUID;

/**
 * 租户行隔离处理器（P-02 → M1-003 fail-fast 收紧版）
 * <p>
 * 为已认证请求的 SQL 自动追加 {@code AND tenant_id = <当前租户>}，构成行级隔离的纵深防线，
 * 弥补此前「隔离仅靠开发者手工 {@code .eq(tenantId)}」的漏防风险（见 design/07 §11）。
 * <ul>
 *   <li><b>公共标识表</b> {@code tenants}（public schema，tenant_id 为主键身份而非归属列）→ 恒定忽略，
 *       否则平台级「列出所有租户」会被误注入条件。</li>
 *   <li><b>系统作用域</b>（{@link TenantContextHolder#isSystemScope()}）→ 跳过注入。
 *       覆盖显式声明的跨租户链路：登录/注册等前置认证、{@code @Scheduled} 全租户扫描。</li>
 *   <li><b>无租户上下文且非系统作用域</b> → 抛 {@link IllegalStateException} 拒绝执行（fail-fast 铁律，
 *       design/07 §11.4）。原「策略 B 静默跳过」已废止：漏绑定上下文的 SQL 必须在测试期暴露而非带病放行。</li>
 *   <li>其余业务表均含 tenant_id 列，正常注入。</li>
 * </ul>
 * PG 中未定型字符串字面量会隐式转为 uuid 列类型，故用 {@link StringValue} 承载租户 UUID。
 */
public class MindSafeTenantLineHandler implements TenantLineHandler {

    /** 恒定忽略的表（公共标识表与平台级事件表，无租户归属语义）。 */
    private static final Set<String> IGNORE_TABLES = Set.of(
            "tenants",
            // OPS-MON-007/008（V34）：降级/告警事件为平台级表（无 tenant_id 列），
            // 否则定时任务落库触发 fail-fast / 带租户上下文时注入不存在列导致 SQL 报错（code-review H1）
            "degradation_events",
            "alert_events",
            // ADMIN-P0-01（V35）：平台账号与健康快照为平台级表（无 tenant_id 列）
            "platform_admin",
            "service_health_snapshots",
            // ADMIN-P1-01/05（V36）：配置注册表/历史为平台级表；sla_escalation_log 无 tenant_id 列
            "sys_config",
            "sys_config_history",
            "sla_escalation_log",
            // CFG-001/004/005（V39）：无屏终端设备表为平台级表（无 tenant_id 列）
            "device",
            "device_bindings",
            "device_bind_codes",
            "device_qr_issuance");

    @Override
    public Expression getTenantId() {
        UUID tenantId = TenantContextHolder.get();
        if (tenantId == null) {
            // 理论不可达：ignoreTable 已在无上下文时抛出或返回 true
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
        // 公共标识表恒定忽略（在 fail-fast 之前判断：前置过滤器查 tenants 表属合法路径）
        if (IGNORE_TABLES.contains(normalize(tableName))) {
            return true;
        }
        // 显式系统作用域：跨租户链路（前置认证/定时任务）跳过注入
        if (TenantContextHolder.isSystemScope()) {
            return true;
        }
        // fail-fast 铁律（M1-003）：无上下文且未声明系统作用域 → 拒绝执行
        if (TenantContextHolder.get() == null) {
            throw new IllegalStateException(
                    "[租户 fail-fast] 表 " + tableName + " 的 SQL 在无租户上下文且非系统作用域下执行被拒绝；"
                            + "前置认证/定时任务等合法跨租户链路请用 TenantContextHolder.runAsSystem/callAsSystem 显式声明（M1-003）");
        }
        return false;
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
