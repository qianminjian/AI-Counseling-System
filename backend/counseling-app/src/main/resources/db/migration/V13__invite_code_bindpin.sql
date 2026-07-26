-- V13: 邀请码一人一码 + 学生 PIN 码
-- AUTH-010: 扩展 trial_invite_codes 支持绑定用户、批次管理
-- AUTH-011: users 表新增 pin_hash 字段

-- ===== 邀请码扩展（一人一码） =====
ALTER TABLE tenant_template.trial_invite_codes
    ADD COLUMN IF NOT EXISTS bound_user_id UUID,
    ADD COLUMN IF NOT EXISTS used_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS batch_id VARCHAR(50),
    ADD COLUMN IF NOT EXISTS generated_by UUID;

COMMENT ON COLUMN tenant_template.trial_invite_codes.bound_user_id IS '绑定的用户ID（一人一码，用后填入）';
COMMENT ON COLUMN tenant_template.trial_invite_codes.used_at IS '实际使用时间';
COMMENT ON COLUMN tenant_template.trial_invite_codes.batch_id IS '批次号（教师批量生成时标记）';
COMMENT ON COLUMN tenant_template.trial_invite_codes.generated_by IS '生成者（教师 userId）';

-- ===== 学生 PIN 码 =====
ALTER TABLE tenant_template.users
    ADD COLUMN IF NOT EXISTS pin_hash VARCHAR(100),
    ADD COLUMN IF NOT EXISTS pin_set_at TIMESTAMPTZ;

COMMENT ON COLUMN tenant_template.users.pin_hash IS 'PIN码BCrypt哈希（4-6位数字）';
COMMENT ON COLUMN tenant_template.users.pin_set_at IS 'PIN码设置时间';
