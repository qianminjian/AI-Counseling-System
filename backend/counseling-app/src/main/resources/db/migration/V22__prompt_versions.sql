-- AI-005: Prompt 版本管理与 A/B 测试框架
-- prompt_versions: 存储版本化的 Prompt 模板（DB 优先，classpath 降级）

CREATE TABLE IF NOT EXISTS tenant_template.prompt_versions (
    version_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID,                          -- NULL = 全局默认（平台级）
    template_key    VARCHAR(50) NOT NULL,          -- 模板标识，如 SYS_001, SKL_001
    version         INTEGER NOT NULL,              -- 递增版本号
    content         TEXT NOT NULL,                 -- Prompt 模板内容（含 {{var}} 占位符）
    description     VARCHAR(500),                  -- 版本变更说明
    ab_group        VARCHAR(20) NOT NULL DEFAULT 'control',  -- control / treatment_a / treatment_b
    is_active       BOOLEAN NOT NULL DEFAULT false,          -- 是否为当前生效版本
    created_by      UUID,                          -- 创建者（管理员）
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_prompt_version UNIQUE (tenant_id, template_key, version, ab_group)
);

-- 索引：按模板+分组快速查找生效版本
CREATE INDEX IF NOT EXISTS idx_prompt_versions_active
    ON tenant_template.prompt_versions (template_key, ab_group, is_active)
    WHERE is_active = true;

CREATE INDEX IF NOT EXISTS idx_prompt_versions_tenant
    ON tenant_template.prompt_versions (tenant_id, template_key);

-- counseling_sessions 新增 prompt_version 字段（记录会话使用的 Prompt 版本，用于 A/B 效果对比）
ALTER TABLE tenant_template.counseling_sessions
    ADD COLUMN IF NOT EXISTS prompt_version VARCHAR(100);

COMMENT ON TABLE tenant_template.prompt_versions IS 'AI-005: Prompt 版本管理（支持 A/B 测试分组）';
COMMENT ON COLUMN tenant_template.prompt_versions.ab_group IS 'A/B 分组: control=对照组, treatment_a/b=实验组';
COMMENT ON COLUMN tenant_template.counseling_sessions.prompt_version IS 'AI-005: 会话使用的 Prompt 版本标识（如 SYS_001:v3:treatment_a）';
