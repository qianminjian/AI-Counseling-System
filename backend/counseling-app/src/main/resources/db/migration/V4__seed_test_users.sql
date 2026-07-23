-- V4: M1 开发测试用户（学生 + 心理老师）
-- 用于本地开发和端到端验证

-- 测试学生
INSERT INTO tenant_template.users (user_id, tenant_id, school_id, user_type, pseudonym, grade_code, class_code, status)
VALUES (
    '20000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000011',
    'student',
    '小明',
    'grade5',
    'class1',
    'active'
) ON CONFLICT (user_id) DO NOTHING;

-- 测试心理老师
INSERT INTO tenant_template.users (user_id, tenant_id, school_id, user_type, pseudonym, status)
VALUES (
    '20000000-0000-0000-0000-000000000002',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000011',
    'psych_teacher',
    '李老师',
    'active'
) ON CONFLICT (user_id) DO NOTHING;

-- 测试班主任
INSERT INTO tenant_template.users (user_id, tenant_id, school_id, user_type, pseudonym, grade_code, class_code, status)
VALUES (
    '20000000-0000-0000-0000-000000000003',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000011',
    'head_teacher',
    '王老师',
    'grade5',
    'class1',
    'active'
) ON CONFLICT (user_id) DO NOTHING;

-- 用户角色绑定
INSERT INTO tenant_template.user_roles (user_role_id, tenant_id, user_id, role_id, school_id)
VALUES
    ('30000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000011'),
    ('30000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000011'),
    ('30000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000011')
ON CONFLICT (user_role_id) DO NOTHING;
