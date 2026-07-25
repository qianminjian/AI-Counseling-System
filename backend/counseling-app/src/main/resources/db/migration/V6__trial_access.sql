-- V6: 试用准入（P0）
-- 1. users 加 must_change_password（首次设密机制，方案 B）
-- 2. trial_invite_codes 邀请码表
-- 3. consent_records 同意留痕表
-- 4. 种子：试用租户 + 试用学校 + 咨询师 minjianq + 邀请码

-- ===== 1. users 加首次改密标记 =====
ALTER TABLE tenant_template.users
    ADD COLUMN IF NOT EXISTS must_change_password BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN tenant_template.users.must_change_password
    IS '首次登录强制改密标记（方案 B：临时密码 + 首次改密）';

-- ===== 2. 邀请码表 =====
CREATE TABLE IF NOT EXISTS tenant_template.trial_invite_codes (
    code_id         UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id       UUID         NOT NULL,
    code            VARCHAR(32)  NOT NULL,
    max_uses        INT          NOT NULL DEFAULT 1,
    used_count      INT          NOT NULL DEFAULT 0,
    expires_at      TIMESTAMPTZ,
    status          VARCHAR(20)  NOT NULL DEFAULT 'active',
    created_by      UUID,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_invite_code UNIQUE (tenant_id, code)
);

CREATE INDEX idx_invite_code_lookup
    ON tenant_template.trial_invite_codes(tenant_id, code, status);

COMMENT ON TABLE tenant_template.trial_invite_codes
    IS '试用邀请码：控制试用准入人群范围';

-- ===== 3. 同意留痕表 =====
CREATE TABLE IF NOT EXISTS tenant_template.consent_records (
    consent_id      UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id         UUID         NOT NULL,
    tenant_id       UUID         NOT NULL,
    consent_type    VARCHAR(32)  NOT NULL,
    consent_version VARCHAR(16)  NOT NULL,
    consented_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    ip_hash         VARCHAR(64),
    user_agent      VARCHAR(255)
);

CREATE INDEX idx_consent_user
    ON tenant_template.consent_records(tenant_id, user_id, consent_type);

COMMENT ON TABLE tenant_template.consent_records
    IS '告知同意留痕：版本化记录，每次版本升级需重新同意';

-- ===== 4. 种子数据 =====

-- 4.1 试用租户（固定 UUID，所有试用用户归属此租户）
INSERT INTO tenants (tenant_id, tenant_code, tenant_name, status)
VALUES ('90000000-0000-0000-0000-000000000001', 'TRIAL', 'MindSafe 公网试用租户', 'active')
ON CONFLICT (tenant_code) DO NOTHING;

-- 4.2 试用学校（试用租户下的虚拟学校）
INSERT INTO schools (school_id, tenant_id, school_code, school_name, edu_stage, status)
VALUES ('90000000-0000-0000-0000-000000000011', '90000000-0000-0000-0000-000000000001', 'TRIAL-SCHOOL', '试用虚拟学校', 'primary', 'active')
ON CONFLICT (tenant_id, school_code) DO NOTHING;

-- 4.3 试用咨询师 minjianq（钱敏健本人，方案 B：临时密码 + 首次强制改密）
-- ⚠️ 临时密码: Trial@MindSafe2026!（首次登录后必须修改）
INSERT INTO tenant_template.users (
    user_id, tenant_id, school_id, user_type, pseudonym,
    status, password_hash, must_change_password
)
VALUES (
    '90000000-0000-0000-0000-000000000002',
    '90000000-0000-0000-0000-000000000001',
    '90000000-0000-0000-0000-000000000011',
    'psych_teacher',
    'minjianq',
    'active',
    '$2a$10$Zw1t498ud1DXDEBUUlYzQu2IHBy/cx69RJAbB0ZdwS8P7ziDZqF5C',
    true
) ON CONFLICT (user_id) DO NOTHING;

-- 4.4 试用咨询师角色绑定（psych_teacher）
INSERT INTO tenant_template.user_roles (user_role_id, tenant_id, user_id, role_id, school_id)
VALUES (
    '90000000-0000-0000-0000-000000000003',
    '90000000-0000-0000-0000-000000000001',
    '90000000-0000-0000-0000-000000000002',
    '10000000-0000-0000-0000-000000000002',
    '90000000-0000-0000-0000-000000000011'
) ON CONFLICT (user_role_id) DO NOTHING;

-- 4.5 试用邀请码（首批 3 个，各限 50 次使用，6 个月有效）
INSERT INTO tenant_template.trial_invite_codes (code_id, tenant_id, code, max_uses, expires_at, status)
VALUES
    ('91000000-0000-0000-0000-000000000001', '90000000-0000-0000-0000-000000000001', 'MINDSAFE-TRIAL-001', 50, now() + interval '6 months', 'active'),
    ('91000000-0000-0000-0000-000000000002', '90000000-0000-0000-0000-000000000001', 'MINDSAFE-TRIAL-002', 50, now() + interval '6 months', 'active'),
    ('91000000-0000-0000-0000-000000000003', '90000000-0000-0000-0000-000000000001', 'MINDSAFE-TRIAL-003', 50, now() + interval '6 months', 'active')
ON CONFLICT (tenant_id, code) DO NOTHING;
