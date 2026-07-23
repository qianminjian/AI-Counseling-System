-- V5: 用户认证支持（M1 简化版：密码哈希列 + 测试账号密码）
-- M1 使用 BCrypt 哈希，后续迭代迁移到加密字段

ALTER TABLE tenant_template.users ADD COLUMN IF NOT EXISTS password_hash VARCHAR(128);

-- 测试账号密码统一为: 123456（BCrypt 哈希）
UPDATE tenant_template.users SET password_hash = '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi'
WHERE user_id IN (
    '20000000-0000-0000-0000-000000000001',
    '20000000-0000-0000-0000-000000000002',
    '20000000-0000-0000-0000-000000000003'
);
