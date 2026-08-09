-- V36: 后台管理端 P1 表（ADMIN-P1-01/02/05，doing/83 后台管理端 AdminConsole 设计方案）
--
-- 背景：P1 配置与业务核心需要：
--   1) sys_config           配置注册表（M1：配置面板，SECRET 掩码 + HOT/RESTART 两级）
--   2) sys_config_history   配置变更历史（留痕，SECRET 存掩码标记）
--   3) sla_escalation_log   M8 逾期升级留痕（SlaEscalationScanner 升级动作补落库 + 平台转派/强制关闭）
--   4) prompt_versions.status 扩展（M7 审核发布流状态机：draft/pending_review/approved/active/retired）
-- 关联：设计文档 doing/83_后台管理端AdminConsole设计方案.md §6.1/§6.2/§6.9/§6.10。
-- schema 约定：与既有表一致挂 tenant_template（平台表经 TenantLineHandler 忽略名单豁免）。

-- ===== sys_config（配置注册表，§6.1） =====
CREATE TABLE IF NOT EXISTS tenant_template.sys_config (
    config_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    config_key   VARCHAR(128) NOT NULL UNIQUE,       -- 配置键（如 mindsafe.safety.voiceprint-threshold）
    domain       VARCHAR(32)  NOT NULL,              -- system/security/voice/chat/alert/commercial
    value        TEXT,                               -- 配置值（SECRET 类存掩码标记，值不回读）
    value_type   VARCHAR(16)  NOT NULL DEFAULT 'string',  -- string/number/bool/json
    sensitive    VARCHAR(8)   NOT NULL DEFAULT 'NORMAL',  -- NORMAL/SECRET（SECRET 不回显值）
    effect_mode  VARCHAR(8)   NOT NULL DEFAULT 'RESTART', -- HOT/RESTART
    source       VARCHAR(32)  NOT NULL DEFAULT 'db',      -- application.yml/env/python-config/db
    description  VARCHAR(512),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by   VARCHAR(64)
);

COMMENT ON TABLE  tenant_template.sys_config IS '配置注册表（M1，平台级表）';
COMMENT ON COLUMN tenant_template.sys_config.sensitive   IS 'NORMAL=值可读 / SECRET=仅显示已配置未配置，值永不出 API';
COMMENT ON COLUMN tenant_template.sys_config.effect_mode IS 'HOT=修改即时生效 / RESTART=需重启生效（仅标记 HOT 开放修改，R-3）';

-- ===== sys_config_history（配置变更历史，§6.2） =====
CREATE TABLE IF NOT EXISTS tenant_template.sys_config_history (
    history_id  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    config_key  VARCHAR(128) NOT NULL,
    old_value   TEXT,
    new_value   TEXT,                                 -- SECRET 存掩码标记（如 "***已配置***"）
    changed_by  VARCHAR(64)  NOT NULL,                -- 操作人（platform_admin 账号）
    reason      VARCHAR(512),                         -- 变更原因（必填）
    changed_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE tenant_template.sys_config_history IS '配置变更历史（M1，留痕）';

CREATE INDEX IF NOT EXISTS idx_sys_config_history_key_time
    ON tenant_template.sys_config_history (config_key, changed_at DESC);

-- ===== sla_escalation_log（M8 逾期升级留痕，§6.9） =====
CREATE TABLE IF NOT EXISTS tenant_template.sla_escalation_log (
    escalation_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    risk_event_id UUID NOT NULL,                      -- 关联预警（索引）
    stage         VARCHAR(16) NOT NULL,               -- ack（认领）/handle（处置）/follow_up（回访）
    expected_at   TIMESTAMPTZ,                        -- SLA 应完成时间点
    escalated_at  TIMESTAMPTZ NOT NULL DEFAULT now(), -- 实际升级时间
    action        VARCHAR(32) NOT NULL,               -- notify_escalate/transfer/force_close
    operator      VARCHAR(64),                        -- 平台操作人（可空=自动升级）
    detail        VARCHAR(512)                        -- 升级说明/处置意见
);

COMMENT ON TABLE  tenant_template.sla_escalation_log IS 'SLA 逾期升级留痕（M8，平台级表：无 tenant_id 列）';
COMMENT ON COLUMN tenant_template.sla_escalation_log.action IS 'notify_escalate=通知升级（扫描器自动）/transfer=转派/force_close=强制关闭（平台操作）';

CREATE INDEX IF NOT EXISTS idx_sla_escalation_log_risk_event
    ON tenant_template.sla_escalation_log (risk_event_id, escalated_at DESC);

-- ===== prompt_versions.status 扩展（M7，§6.10） =====
-- NOT NULL DEFAULT 'draft'：新建版本默认草稿（code-review H1：可空列会使 NULL 绕过激活门禁）
ALTER TABLE tenant_template.prompt_versions
    ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'draft';

-- 历史数据回填：is_active=true → active，其余 → approved（§6.10 兼容策略）
UPDATE tenant_template.prompt_versions
   SET status = CASE WHEN is_active THEN 'active' ELSE 'approved' END
 WHERE status IS NULL;

COMMENT ON COLUMN tenant_template.prompt_versions.status
    IS '审核发布流状态: draft/pending_review/approved/active/retired（M7，§6.10；is_active 保留兼容，激活时两者同步）';

-- 审核流查询索引（待审核清单/按状态筛选）
CREATE INDEX IF NOT EXISTS idx_prompt_versions_status
    ON tenant_template.prompt_versions (status);
