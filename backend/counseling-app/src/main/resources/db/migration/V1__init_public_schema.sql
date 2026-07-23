-- V1: 公共 Schema 初始化（租户注册表 + 学校表）
-- 公共 Schema 存放不按租户隔离的全局数据

-- 租户表（隔离根）
CREATE TABLE IF NOT EXISTS tenants (
    tenant_id       UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_code     VARCHAR(64)  NOT NULL,
    tenant_name     VARCHAR(128) NOT NULL,
    data_region     VARCHAR(32)  DEFAULT 'cn-east',
    kms_key_ref     VARCHAR(128),
    status          VARCHAR(20)  NOT NULL DEFAULT 'active',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    CONSTRAINT uq_tenants_code UNIQUE (tenant_code)
);

CREATE INDEX idx_tenants_status ON tenants(status);

COMMENT ON TABLE tenants IS '租户表：SaaS 隔离根，一个租户对应一个独立 Schema';

-- 学校表
CREATE TABLE IF NOT EXISTS schools (
    school_id       UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id       UUID         NOT NULL REFERENCES tenants(tenant_id),
    school_code     VARCHAR(64)  NOT NULL,
    school_name     VARCHAR(128) NOT NULL,
    edu_stage       VARCHAR(32)  DEFAULT 'primary',
    province        VARCHAR(64),
    city            VARCHAR(64),
    district        VARCHAR(64),
    settings        JSONB        DEFAULT '{}',
    status          VARCHAR(20)  NOT NULL DEFAULT 'active',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    CONSTRAINT uq_schools_tenant_code UNIQUE (tenant_id, school_code)
);

CREATE INDEX idx_schools_tenant_status ON schools(tenant_id, status);

COMMENT ON TABLE schools IS '学校表：一个租户下可有多所学校';
