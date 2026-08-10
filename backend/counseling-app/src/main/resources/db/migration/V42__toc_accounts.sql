-- V42: toC 家庭账号与孩子档案表（TOC-001/002，doing/85 波波小伙伴 toC 家庭版 §四）
--
-- 背景：toC 家庭版账号体系——手机号验证码注册/登录，独立于校园账号体系
-- （ParentAccount/family_code 是校园绑定型，本表为家庭自有账号）；多孩档案
-- 一账号多档案，数据按 family_account_id 隔离。
-- 设计文档单一事实源：design/doing/85_波波小伙伴toC家庭版_方案与SPEC.md §四/§五
-- schema 约定：与既有平台表一致挂 tenant_template（无 tenant_id 列，平台级；
-- TenantLineHandler 忽略名单同步追加，见 MindSafeTenantLineHandler）。

-- ===== toc_family_accounts（toC 家庭账号，TOC-001） =====
CREATE TABLE IF NOT EXISTS tenant_template.toc_family_accounts (
    family_account_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    phone             VARCHAR(20)  NOT NULL,           -- 手机号（登录标识，唯一）
    status            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE/DISABLED
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_toc_family_phone UNIQUE (phone)
);

COMMENT ON TABLE  tenant_template.toc_family_accounts IS 'toC 家庭账号（TOC-001；平台级表，无 tenant_id 列，独立于校园体系）';
COMMENT ON COLUMN tenant_template.toc_family_accounts.status IS 'ACTIVE=正常 / DISABLED=禁用（隐私控制关闭设备/账号）';

-- ===== toc_child_profiles（孩子档案，TOC-002） =====
CREATE TABLE IF NOT EXISTS tenant_template.toc_child_profiles (
    profile_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    family_account_id UUID         NOT NULL,           -- 归属家庭账号（数据隔离键）
    nickname          VARCHAR(50)  NOT NULL,           -- 昵称（复用学生档案核心字段语义）
    age               INT,                             -- 年龄
    gender            VARCHAR(16),                     -- MALE/FEMALE/UNSPECIFIED
    interests         VARCHAR(500),                    -- 兴趣（逗号分隔，成长报告算法输入）
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_toc_child_profiles_account ON tenant_template.toc_child_profiles(family_account_id);

COMMENT ON TABLE  tenant_template.toc_child_profiles IS 'toC 孩子档案（TOC-002；平台级表，一账号多孩，按 family_account_id 隔离）';
COMMENT ON COLUMN tenant_template.toc_child_profiles.interests IS '兴趣标签（逗号分隔；成长报告 TOC-004 算法输入，非原始对话）';
