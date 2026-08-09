-- V35: 后台管理端 P0 底座表（ADMIN-P0-01，doing/83 后台管理端 AdminConsole 设计方案）
--
-- 背景：平台管理端（admin-web）P0 底座需要两张平台级表（无 tenant_id 列，
-- 属平台表，TenantLineHandler 忽略名单范围）：
--   1) platform_admin           平台管理员账号（独立于租户 users 表，DEC-007：
--                               独立表 + 独立登录端点 + PLATFORM_ token 前缀）
--   2) service_health_snapshots 服务健康快照（M2 服务拓扑历史曲线/SLA 统计，
--                               30s 采样粒度）
-- 关联：设计文档 doing/83_后台管理端AdminConsole设计方案.md §6.3/§6.8（表结构单一事实源）。

-- ===== platform_admin（平台管理员账号，§6.8） =====
CREATE TABLE IF NOT EXISTS tenant_template.platform_admin (
    admin_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username      VARCHAR(64)  NOT NULL UNIQUE,      -- 登录名（唯一）
    password_hash VARCHAR(100) NOT NULL,             -- BCrypt
    role          VARCHAR(16)  NOT NULL,             -- super_admin/ops_admin/finance_admin/audit
    display_name  VARCHAR(64),
    status        VARCHAR(16)  NOT NULL DEFAULT 'active',  -- active/disabled
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_login_at TIMESTAMPTZ
);

COMMENT ON TABLE  tenant_template.platform_admin IS '平台管理员账号（M6，独立于租户 users 表，DEC-007）';
COMMENT ON COLUMN tenant_template.platform_admin.role   IS '角色: super_admin/ops_admin/finance_admin/audit（四角色 RBAC）';
COMMENT ON COLUMN tenant_template.platform_admin.status IS '状态: active/disabled（禁用账号拒绝登录）';

-- ===== service_health_snapshots（服务健康快照，§6.3） =====
CREATE TABLE IF NOT EXISTS tenant_template.service_health_snapshots (
    snapshot_id BIGSERIAL PRIMARY KEY,
    service     VARCHAR(16)  NOT NULL,               -- postgres/redis/tts/voice/backend/nginx
    status      VARCHAR(16)  NOT NULL,               -- UP/DEGRADED/DOWN
    detail      JSONB,                               -- 引擎/就绪态等附加信息
    sampled_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE  tenant_template.service_health_snapshots IS '服务健康快照（M2 服务拓扑历史/SLA 验证，30s 采样，保留 30 天）';
COMMENT ON COLUMN tenant_template.service_health_snapshots.status IS '状态: UP/DEGRADED/DOWN（语义对齐 service-manager）';

-- 历史曲线查询索引（按服务 + 时间倒序）
CREATE INDEX IF NOT EXISTS idx_service_health_snapshots_service_time
    ON tenant_template.service_health_snapshots (service, sampled_at DESC);
