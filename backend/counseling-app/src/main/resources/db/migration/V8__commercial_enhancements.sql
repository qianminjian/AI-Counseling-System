-- V8: 商业化补全 - turn_count 列 + 演示种子数据
-- Phase 5: 消息摘要持久化 + 会话元数据

-- ===== 1. counseling_sessions 增加 turn_count =====
ALTER TABLE tenant_template.counseling_sessions
    ADD COLUMN IF NOT EXISTS turn_count INT DEFAULT 0;

COMMENT ON COLUMN tenant_template.counseling_sessions.turn_count IS '对话轮次数（会话结束时写入）';

-- ===== 2. 演示邀请码（测试环境用） =====
INSERT INTO tenant_template.trial_invite_codes (code_id, tenant_id, code, max_uses, used_count, expires_at, status, created_by, created_at)
SELECT
    uuid_generate_v4(),
    t.tenant_id,
    'DEMO2026',
    100,
    0,
    now() + interval '365 days',
    'active',
    NULL,
    now()
FROM public.tenants t
WHERE NOT EXISTS (
    SELECT 1 FROM tenant_template.trial_invite_codes WHERE code = 'DEMO2026'
)
LIMIT 1;

-- ===== 3. 演示学生（如果 V4 已有则跳过） =====
-- 注意：V4 已创建测试用户，此处仅补充额外演示学生
DO $$
DECLARE
    v_tenant_id UUID;
BEGIN
    SELECT tenant_id INTO v_tenant_id FROM public.tenants LIMIT 1;
    IF v_tenant_id IS NULL THEN
        RETURN;
    END IF;

    -- 插入 5 个演示学生（如果不存在）
    IF NOT EXISTS (SELECT 1 FROM tenant_template.users WHERE pseudonym = '小明' AND tenant_id = v_tenant_id) THEN
        INSERT INTO tenant_template.users (user_id, tenant_id, user_type, pseudonym, grade_code, class_code, status, created_at, updated_at)
        VALUES
            (uuid_generate_v4(), v_tenant_id, 'student', '小明', 'grade_5', 'class_1', 'active', now(), now()),
            (uuid_generate_v4(), v_tenant_id, 'student', '小红', 'grade_5', 'class_1', 'active', now(), now()),
            (uuid_generate_v4(), v_tenant_id, 'student', '小刚', 'grade_4', 'class_2', 'active', now(), now()),
            (uuid_generate_v4(), v_tenant_id, 'student', '小美', 'grade_6', 'class_1', 'active', now(), now()),
            (uuid_generate_v4(), v_tenant_id, 'student', '小杰', 'grade_6', 'class_2', 'active', now(), now());
    END IF;
END $$;
