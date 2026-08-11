package com.mindsafe.migration;

import com.mindsafe.tenant.MindSafeTenantLineHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * AD-003 迁移一致性测试（2026-08-11）：
 * 平台级表豁免名单 vs Flyway 迁移文件 schema 实态的一致性断言。
 * <p>
 * 判定规则（AD-003：可推导规则替代手写退化）：tenant_template schema 下
 * <b>无 tenant_id 列</b>的表 = 平台级表 → 必须登记 IGNORE_TABLES（否则行级隔离
 * 注入不存在列导致 SQL 500 / fail-fast）；有 tenant_id 列的表不应误入名单。
 * 反向断言防名单悬空（登记了不存在的表）。
 */
class MigrationTenantConsistencyTest {

    /** 迁移文件根（counseling-app 模块内，测试工作目录 = 模块目录） */
    private static final Path MIGRATIONS_DIR = Path.of("src/main/resources/db/migration");

    private static final Pattern CREATE_TABLE_PATTERN = Pattern.compile(
            "CREATE TABLE(?: IF NOT EXISTS)? tenant_template\\.(\\w+)\\s*\\((.*?)\\);",
            Pattern.DOTALL);

    @Test
    @DisplayName("平台级表（无 tenant_id 列）全部登记 IGNORE_TABLES（AD-003 正向断言）")
    void platformTablesAllRegistered() throws IOException {
        Set<String> registered = MindSafeTenantLineHandler.ignoredTables();
        List<String> missing = new ArrayList<>();

        for (TableDef table : scanTables()) {
            boolean hasTenantColumn = table.columns().contains("tenant_id");
            if (!hasTenantColumn && !registered.contains(table.name())) {
                missing.add(table.name());
            }
        }
        assertThat(missing)
                .as("无 tenant_id 列的平台级表未登记 IGNORE_TABLES（行级隔离将注入不存在列）")
                .isEmpty();
    }

    @Test
    @DisplayName("租户表（有 tenant_id 列）不应误入 IGNORE_TABLES（AD-003 反向断言）")
    void tenantTablesNotInIgnoreList() throws IOException {
        Set<String> registered = MindSafeTenantLineHandler.ignoredTables();
        List<String> wronglyIgnored = new ArrayList<>();

        for (TableDef table : scanTables()) {
            if (table.columns().contains("tenant_id") && registered.contains(table.name())) {
                wronglyIgnored.add(table.name());
            }
        }
        // tenants 例外：public schema 主键身份表，恒定忽略（设计注释）
        wronglyIgnored.remove("tenants");
        assertThat(wronglyIgnored)
                .as("有 tenant_id 列的租户表被误登记 IGNORE_TABLES（行级隔离失效）")
                .isEmpty();
    }

    @Test
    @DisplayName("IGNORE_TABLES 无悬空条目（AD-003 名单退化断言）")
    void noDanglingEntries() throws IOException {
        Set<String> schemaTables = scanTables().stream()
                .map(TableDef::name).collect(java.util.stream.Collectors.toSet());
        // tenants 为 public schema（不在迁移文件 tenant_template 扫描范围）——白名单豁免
        Set<String> expected = new HashSet<>(MindSafeTenantLineHandler.ignoredTables());
        expected.remove("tenants");

        List<String> dangling = expected.stream()
                .filter(t -> !schemaTables.contains(t))
                .toList();
        assertThat(dangling)
                .as("IGNORE_TABLES 登记了迁移文件中不存在的表（名单悬空退化）")
                .isEmpty();
    }

    /** 扫描全部迁移文件，提取 tenant_template schema 建表定义（表名 + 列名集合） */
    private List<TableDef> scanTables() throws IOException {
        List<TableDef> tables = new ArrayList<>();
        try (var stream = Files.list(MIGRATIONS_DIR)) {
            for (Path file : stream.filter(p -> p.toString().endsWith(".sql")).toList()) {
                String sql = Files.readString(file, StandardCharsets.UTF_8);
                Matcher m = CREATE_TABLE_PATTERN.matcher(sql);
                while (m.find()) {
                    tables.add(new TableDef(m.group(1), extractColumns(m.group(2))));
                }
            }
        }
        if (tables.isEmpty()) {
            fail("迁移目录未扫描到 tenant_template 建表语句：" + MIGRATIONS_DIR.toAbsolutePath());
        }
        return tables;
    }

    /** 提取列定义中的列名（首 token，跳过括号内默认值等干扰） */
    private Set<String> extractColumns(String body) {
        Set<String> columns = new HashSet<>();
        // 逐行解析：行首缩进后第一个标识符为列名（排除 CONSTRAINT/PRIMARY/UNIQUE/INDEX/COMMENT 行）
        for (String line : body.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                continue;
            }
            String upper = trimmed.toUpperCase();
            if (upper.startsWith("CONSTRAINT") || upper.startsWith("PRIMARY")
                    || upper.startsWith("UNIQUE") || upper.startsWith("INDEX")
                    || upper.startsWith("CHECK") || upper.startsWith("COMMENT")
                    || upper.startsWith("FOREIGN") || upper.startsWith("REFERENCES")) {
                continue;
            }
            Matcher cm = Pattern.compile("^([a-z_][a-z0-9_]*)").matcher(trimmed);
            if (cm.find()) {
                columns.add(cm.group(1));
            }
        }
        return columns;
    }

    private record TableDef(String name, Set<String> columns) {}
}
