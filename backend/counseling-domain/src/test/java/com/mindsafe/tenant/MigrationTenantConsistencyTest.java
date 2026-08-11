package com.mindsafe.tenant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 迁移与租户忽略名单一致性测试（doing/91 Q-002，AC：平台表必入名单/租户表必不在名单）
 * <p>
 * 规则（可推导，替代手工维护易错接缝）：
 * - 迁移文件中无 tenant_id 列的表 = 平台级表 → 必须 ∈ IGNORE_TABLES（漏加则注入不存在列 SQL 500）
 * - 有 tenant_id 列的表 = 租户表 → 必须 ∉ IGNORE_TABLES（误加则租户隔离失效）
 */
class MigrationTenantConsistencyTest {

    /** 迁移文件目录（counseling-app，相对 domain 模块测试运行目录） */
    private static final Path MIGRATION_DIR = Path.of(
            "../counseling-app/src/main/resources/db/migration").toAbsolutePath().normalize();

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "CREATE TABLE\\s+(?:IF NOT EXISTS\\s+)?(?:[\\w]+\\.)?(\\w+)\\s*\\(([^;]*?)\\)\\s*;",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** 公共标识表豁免：含 tenant_id 列但在名单中是设计（租户定义表自身无租户归属语义） */
    private static final Set<String> COMMON_TABLES_EXEMPT = Set.of("tenants");

    /** 反射读取 IGNORE_TABLES（避免依赖包内可见性） */
    @SuppressWarnings("unchecked")
    private static Set<String> ignoreTables() throws Exception {
        Field f = MindSafeTenantLineHandler.class.getDeclaredField("IGNORE_TABLES");
        f.setAccessible(true);
        return (Set<String>) f.get(null);
    }

    private record TableInfo(String name, boolean hasTenantId) {
    }

    private static Set<TableInfo> parseMigrations() throws IOException {
        Set<TableInfo> tables = new LinkedHashSet<>();
        if (!Files.isDirectory(MIGRATION_DIR)) {
            throw new IllegalStateException("迁移目录不存在: " + MIGRATION_DIR);
        }
        try (var stream = Files.list(MIGRATION_DIR)) {
            for (Path p : stream.filter(f -> f.getFileName().toString().endsWith(".sql")).sorted().toList()) {
                String sql = Files.readString(p);
                Matcher m = CREATE_TABLE.matcher(sql);
                while (m.find()) {
                    String name = m.group(1).toLowerCase();
                    String body = m.group(2);
                    tables.add(new TableInfo(name, body.contains("tenant_id")));
                }
            }
        }
        return tables;
    }

    @Test
    @DisplayName("Q-002：平台表（无 tenant_id 列）必须全部在忽略名单中")
    void platformTablesMustBeIgnored() throws Exception {
        Set<String> ignore = ignoreTables();
        var violations = new LinkedHashSet<String>();
        for (TableInfo t : parseMigrations()) {
            if (!t.hasTenantId() && !ignore.contains(t.name())) {
                violations.add(t.name());
            }
        }
        assertThat(violations)
                .as("无 tenant_id 列的平台表漏加 IGNORE_TABLES（下个平台表必踩）")
                .isEmpty();
    }

    @Test
    @DisplayName("Q-002：租户表（含 tenant_id 列）不得误入忽略名单")
    void tenantTablesMustNotBeIgnored() throws Exception {
        Set<String> ignore = ignoreTables();
        var violations = new LinkedHashSet<String>();
        for (TableInfo t : parseMigrations()) {
            if (t.hasTenantId() && ignore.contains(t.name()) && !COMMON_TABLES_EXEMPT.contains(t.name())) {
                violations.add(t.name());
            }
        }
        assertThat(violations)
                .as("含 tenant_id 列的租户表误入 IGNORE_TABLES（租户隔离失效）")
                .isEmpty();
    }

    @Test
    @DisplayName("Q-002：忽略名单中的表必须真实存在于迁移文件（防僵尸条目）")
    void ignoredTablesMustExist() throws Exception {
        Set<String> ignore = ignoreTables();
        Set<String> migrationTables = parseMigrations().stream()
                .map(TableInfo::name).collect(java.util.stream.Collectors.toSet());
        var missing = ignore.stream().filter(t -> !migrationTables.contains(t)).toList();
        assertThat(missing)
                .as("忽略名单含迁移中不存在的表（僵尸条目/拼写错误）")
                .isEmpty();
    }
}
