-- ============================================================
-- MindSafe 默认静态数据（种子脚本）
-- 合并 Flyway V3/V4/V6 种子数据最终态 | 生成日期：2026-08-02
-- 用途：新环境建库后初始化必需静态数据
-- 前置：先执行 01_schema.sql
-- ============================================================

-- ==================== 1. 系统预置角色 ====================
-- 写入 tenant_template，新租户注册时自动复制

INSERT INTO tenant_template.roles (role_id, tenant_id, role_code, role_name, scope_level, permission_set, is_system)
VALUES
    ('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'student',       '学生',       'school', '["session.create", "session.own"]', true),
    ('10000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'psych_teacher', '心理老师',   'school', '["alert.view", "alert.claim", "case.manage", "report.read_full", "session.view_all"]', true),
    ('10000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001', 'head_teacher',  '班主任',     'class',  '["alert.view_class", "report.read_summary", "student.view_class"]', true),
    ('10000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000001', 'school_admin',  '学校管理员', 'school', '["user.manage", "school.settings", "report.export", "audit.view"]', true)
ON CONFLICT (tenant_id, role_code) DO NOTHING;

-- ==================== 2. 开发测试租户与学校 ====================

INSERT INTO tenants (tenant_id, tenant_code, tenant_name, status)
VALUES ('00000000-0000-0000-0000-000000000001', 'DEV', '开发测试租户', 'active')
ON CONFLICT (tenant_code) DO NOTHING;

INSERT INTO schools (school_id, tenant_id, school_code, school_name, edu_stage, status)
VALUES ('00000000-0000-0000-0000-000000000011', '00000000-0000-0000-0000-000000000001', 'DEV-SCHOOL-01', '开发测试小学', 'primary', 'active')
ON CONFLICT (tenant_id, school_code) DO NOTHING;

-- ==================== 3. 开发测试用户 ====================
-- 密码统一: 123456（BCrypt）| 生产环境已 disabled（V25）

INSERT INTO tenant_template.users (user_id, tenant_id, school_id, user_type, pseudonym, grade_code, class_code, status, password_hash, password_changed_at)
VALUES
    ('20000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000011', 'student',       '小明',   'grade5', 'class1', 'disabled', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', now()),
    ('20000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000011', 'psych_teacher', '李老师', NULL,     NULL,     'disabled', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', now()),
    ('20000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000011', 'head_teacher',  '王老师', 'grade5', 'class1', 'disabled', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', now())
ON CONFLICT (user_id) DO NOTHING;

-- 用户角色绑定
INSERT INTO tenant_template.user_roles (user_role_id, tenant_id, user_id, role_id, school_id)
VALUES
    ('30000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000011'),
    ('30000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000011'),
    ('30000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000011')
ON CONFLICT (user_role_id) DO NOTHING;

-- ==================== 4. 公网试用租户 ====================

INSERT INTO tenants (tenant_id, tenant_code, tenant_name, status)
VALUES ('90000000-0000-0000-0000-000000000001', 'TRIAL', 'MindSafe 公网试用租户', 'active')
ON CONFLICT (tenant_code) DO NOTHING;

INSERT INTO schools (school_id, tenant_id, school_code, school_name, edu_stage, status)
VALUES ('90000000-0000-0000-0000-000000000011', '90000000-0000-0000-0000-000000000001', 'TRIAL-SCHOOL', '试用虚拟学校', 'primary', 'active')
ON CONFLICT (tenant_id, school_code) DO NOTHING;

-- 试用咨询师（临时密码: Trial@MindSafe2026!，首次登录强制改密）
INSERT INTO tenant_template.users (user_id, tenant_id, school_id, user_type, pseudonym, status, password_hash, must_change_password)
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

-- 试用咨询师角色绑定
INSERT INTO tenant_template.user_roles (user_role_id, tenant_id, user_id, role_id, school_id)
VALUES (
    '90000000-0000-0000-0000-000000000003',
    '90000000-0000-0000-0000-000000000001',
    '90000000-0000-0000-0000-000000000002',
    '10000000-0000-0000-0000-000000000002',
    '90000000-0000-0000-0000-000000000011'
) ON CONFLICT (user_role_id) DO NOTHING;

-- ==================== 5. 试用邀请码 ====================

INSERT INTO tenant_template.trial_invite_codes (code_id, tenant_id, code, max_uses, expires_at, status)
VALUES
    ('91000000-0000-0000-0000-000000000001', '90000000-0000-0000-0000-000000000001', 'MINDSAFE-TRIAL-001', 50, '2027-06-30 23:59:59+08', 'active'),
    ('91000000-0000-0000-0000-000000000002', '90000000-0000-0000-0000-000000000001', 'MINDSAFE-TRIAL-002', 50, '2027-06-30 23:59:59+08', 'active'),
    ('91000000-0000-0000-0000-000000000003', '90000000-0000-0000-0000-000000000001', 'MINDSAFE-TRIAL-003', 50, '2027-06-30 23:59:59+08', 'active')
ON CONFLICT (tenant_id, code) DO NOTHING;

-- 演示邀请码（长期有效）
INSERT INTO tenant_template.trial_invite_codes (code_id, tenant_id, code, max_uses, expires_at, status)
VALUES
    ('91000000-0000-0000-0000-000000000010', '90000000-0000-0000-0000-000000000001', 'DEMO2026', 100, '2027-12-31 23:59:59+08', 'active')
ON CONFLICT (tenant_id, code) DO NOTHING;

-- ============================================================
-- END OF SEED DATA
-- ============================================================
