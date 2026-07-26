-- V16: 家庭码认证关联体系（FAM-001）
-- 1. users 加 family_code（学生注册时生成，家长绑定凭证）
-- 2. parent_accounts 家长账号表
-- 3. parent_student_links 家长-学生关联表

-- ===== 1. users 加家庭码 =====
ALTER TABLE tenant_template.users
    ADD COLUMN IF NOT EXISTS family_code VARCHAR(6);

CREATE UNIQUE INDEX IF NOT EXISTS idx_users_family_code
    ON tenant_template.users(tenant_id, family_code)
    WHERE family_code IS NOT NULL;

COMMENT ON COLUMN tenant_template.users.family_code
    IS '家庭码（6位字母数字，学生注册时生成，家长绑定凭证）';

-- ===== 2. 家长账号表 =====
CREATE TABLE IF NOT EXISTS tenant_template.parent_accounts (
    parent_id       UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id       UUID         NOT NULL,
    phone           VARCHAR(20)  NOT NULL,
    password_hash   VARCHAR(100) NOT NULL,
    display_name    VARCHAR(30),
    status          VARCHAR(20)  NOT NULL DEFAULT 'active',
    last_login_at   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_parent_phone UNIQUE (tenant_id, phone)
);

CREATE INDEX IF NOT EXISTS idx_parent_accounts_phone
    ON tenant_template.parent_accounts(tenant_id, phone, status);

COMMENT ON TABLE tenant_template.parent_accounts
    IS '家长账号：手机号+密码登录，通过家庭码绑定学生';

-- ===== 3. 家长-学生关联表 =====
CREATE TABLE IF NOT EXISTS tenant_template.parent_student_links (
    link_id         UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id       UUID         NOT NULL,
    parent_id       UUID         NOT NULL REFERENCES tenant_template.parent_accounts(parent_id),
    student_user_id UUID         NOT NULL,
    relation        VARCHAR(20)  NOT NULL DEFAULT 'parent',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_parent_student UNIQUE (parent_id, student_user_id)
);

CREATE INDEX IF NOT EXISTS idx_parent_links_student
    ON tenant_template.parent_student_links(tenant_id, student_user_id);

CREATE INDEX IF NOT EXISTS idx_parent_links_parent
    ON tenant_template.parent_student_links(tenant_id, parent_id);

COMMENT ON TABLE tenant_template.parent_student_links
    IS '家长-学生关联：一个家长可绑多个孩子，一个孩子可绑多个家长';
COMMENT ON COLUMN tenant_template.parent_student_links.relation
    IS '关系：father/mother/grandparent/other';
