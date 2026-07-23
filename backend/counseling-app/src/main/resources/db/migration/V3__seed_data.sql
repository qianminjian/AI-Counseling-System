-- V3: 种子数据（系统预置角色 + 开发用测试租户）

-- 开发/测试用默认租户
INSERT INTO tenants (tenant_id, tenant_code, tenant_name, status)
VALUES ('00000000-0000-0000-0000-000000000001', 'DEV', '开发测试租户', 'active')
ON CONFLICT (tenant_code) DO NOTHING;

-- 开发/测试用默认学校
INSERT INTO schools (school_id, tenant_id, school_code, school_name, edu_stage, status)
VALUES ('00000000-0000-0000-0000-000000000011', '00000000-0000-0000-0000-000000000001', 'DEV-SCHOOL-01', '开发测试小学', 'primary', 'active')
ON CONFLICT (tenant_id, school_code) DO NOTHING;

-- 系统预置角色（写入 tenant_template，新租户复制时自动带入）
INSERT INTO tenant_template.roles (role_id, tenant_id, role_code, role_name, scope_level, permission_set, is_system)
VALUES
    ('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'student', '学生', 'school', '["session.create", "session.own"]', true),
    ('10000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'psych_teacher', '心理老师', 'school', '["alert.view", "alert.claim", "case.manage", "report.read_full", "session.view_all"]', true),
    ('10000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001', 'head_teacher', '班主任', 'class', '["alert.view_class", "report.read_summary", "student.view_class"]', true),
    ('10000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000001', 'school_admin', '学校管理员', 'school', '["user.manage", "school.settings", "report.export", "audit.view"]', true)
ON CONFLICT (tenant_id, role_code) DO NOTHING;
