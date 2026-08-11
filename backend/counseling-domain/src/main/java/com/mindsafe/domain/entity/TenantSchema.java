package com.mindsafe.domain.entity;

/**
 * 租户模板 schema 常量（doing/92 R-020：34 实体 @TableName schema 硬编码收敛单点；
 * 全量 36 个 @TableName 实体，School/Tenant 平台表保留 public schema 不引用）。
 * <p>
 * 业务表统一位于 {@code tenant_template} schema（租户注册时由模板复制，见 design/02 §3）。
 * 实体注解引用本常量，避免字面量漂移；平台级表（tenants 等 public schema）不引用。
 */
public final class TenantSchema {

    /** 租户模板 schema（业务表所在 schema） */
    public static final String TENANT_TEMPLATE = "tenant_template";

    private TenantSchema() {
    }
}
