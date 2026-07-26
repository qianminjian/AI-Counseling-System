-- V14: 密码策略（AUTH-014）
-- 正式账号（教师/管理员）密码 90 天过期，需记录最近一次改密时间
-- 复杂度校验（≥8位 + 字母+数字）由应用层 PasswordPolicyService 负责

ALTER TABLE tenant_template.users
    ADD COLUMN IF NOT EXISTS password_changed_at TIMESTAMPTZ;

COMMENT ON COLUMN tenant_template.users.password_changed_at IS '最近一次密码修改时间（用于 90 天过期判断，NULL 视为从未设置）';

-- 回填：已有密码的账号以迁移时刻为基准，避免存量账号立即被判过期
UPDATE tenant_template.users
SET password_changed_at = now()
WHERE password_hash IS NOT NULL
  AND password_changed_at IS NULL;
