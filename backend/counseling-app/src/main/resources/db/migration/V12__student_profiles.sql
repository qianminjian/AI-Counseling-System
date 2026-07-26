-- V12: 学生心理画像表
-- 存储结构化统计指标（不存原始对话），支持个性化辅导

CREATE TABLE IF NOT EXISTS tenant_template.student_profiles (
    profile_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    user_id             UUID NOT NULL,

    -- 6 大维度（JSONB 增量更新）
    emotion_baseline    JSONB NOT NULL DEFAULT '{}',
    communication_pref  JSONB NOT NULL DEFAULT '{}',
    resilience          JSONB NOT NULL DEFAULT '{}',
    risk_trajectory     JSONB NOT NULL DEFAULT '{}',
    social_graph        JSONB NOT NULL DEFAULT '{}',
    growth_track        JSONB NOT NULL DEFAULT '{}',

    -- 元数据
    version             INT NOT NULL DEFAULT 1,
    total_sessions      INT NOT NULL DEFAULT 0,
    last_updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_student_profile UNIQUE (tenant_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_profile_user
    ON tenant_template.student_profiles(tenant_id, user_id);

COMMENT ON TABLE tenant_template.student_profiles IS '学生心理画像（结构化统计，不存原始对话）';
COMMENT ON COLUMN tenant_template.student_profiles.emotion_baseline IS '情绪基线：分布/波动度/触发主题';
COMMENT ON COLUMN tenant_template.student_profiles.communication_pref IS '沟通偏好：表达深度/偏好风格/活跃时段';
COMMENT ON COLUMN tenant_template.student_profiles.resilience IS '心理韧性：恢复速度/应对技巧/自我效能';
COMMENT ON COLUMN tenant_template.student_profiles.risk_trajectory IS '风险轨迹：等级分布/趋势/敏感主题';
COMMENT ON COLUMN tenant_template.student_profiles.social_graph IS '社交图谱：关键人物(代号化)/满意度/求助意愿';
COMMENT ON COLUMN tenant_template.student_profiles.growth_track IS '成长轨迹：频率/里程碑/干预有效性';
