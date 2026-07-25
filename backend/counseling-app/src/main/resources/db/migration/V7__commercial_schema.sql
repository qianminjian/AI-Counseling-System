-- V7: 商用版扩展 Schema（Phase 2）
-- 新增：counseling_sessions.state_path / audit_logs / model_call_logs
-- 扩展：message_summaries 增加 per-message 粒度字段

-- ===== 1. counseling_sessions 增加 CBT 状态机路径 =====
ALTER TABLE tenant_template.counseling_sessions
    ADD COLUMN IF NOT EXISTS state_path JSONB DEFAULT NULL;

COMMENT ON COLUMN tenant_template.counseling_sessions.state_path IS 'CBT 状态机路径（CbtSessionState JSON）';

-- ===== 2. counseling_sessions 增加满意度评价 =====
ALTER TABLE tenant_template.counseling_sessions
    ADD COLUMN IF NOT EXISTS satisfaction_rating SMALLINT DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS satisfaction_comment VARCHAR(512) DEFAULT NULL;

COMMENT ON COLUMN tenant_template.counseling_sessions.satisfaction_rating IS '会话满意度评分（1-5 星）';

-- ===== 3. message_summaries 增加 per-message 粒度字段 =====
ALTER TABLE tenant_template.message_summaries
    ADD COLUMN IF NOT EXISTS sender_type VARCHAR(20) DEFAULT 'student',
    ADD COLUMN IF NOT EXISTS emotion_label VARCHAR(64) DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS risk_level SMALLINT DEFAULT 0,
    ADD COLUMN IF NOT EXISTS content_summary VARCHAR(1024) DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS cbt_fields JSONB DEFAULT NULL;

COMMENT ON COLUMN tenant_template.message_summaries.sender_type IS '消息发送者类型（student/ai）';
COMMENT ON COLUMN tenant_template.message_summaries.cbt_fields IS 'CBT 结构化字段（emotion_label, auto_thought, balanced_thought 等）';

-- ===== 4. 审计日志表 =====
CREATE TABLE IF NOT EXISTS tenant_template.audit_logs (
    audit_log_id    UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id       UUID         NOT NULL,
    user_id         UUID,
    action          VARCHAR(64)  NOT NULL,
    resource_type   VARCHAR(64)  NOT NULL,
    resource_id     UUID,
    detail          JSONB        DEFAULT NULL,
    ip_hash         CHAR(64),
    user_agent      VARCHAR(256),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_audit_user_time ON tenant_template.audit_logs(tenant_id, user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_resource ON tenant_template.audit_logs(tenant_id, resource_type, resource_id);
CREATE INDEX IF NOT EXISTS idx_audit_action ON tenant_template.audit_logs(tenant_id, action, created_at DESC);

COMMENT ON TABLE tenant_template.audit_logs IS '审计日志表：记录敏感操作（登录/查看学生档案/导出/配置变更）';

-- ===== 5. 模型调用日志表 =====
CREATE TABLE IF NOT EXISTS tenant_template.model_call_logs (
    call_log_id     UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id       UUID         NOT NULL,
    session_id      UUID,
    agent_name      VARCHAR(64)  NOT NULL,
    prompt_version  VARCHAR(64),
    model_version   VARCHAR(128),
    input_tokens    INT          DEFAULT 0,
    output_tokens   INT          DEFAULT 0,
    total_tokens    INT          DEFAULT 0,
    latency_ms      INT          DEFAULT 0,
    status          VARCHAR(20)  NOT NULL DEFAULT 'success',
    error_message   VARCHAR(512),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_model_call_session ON tenant_template.model_call_logs(session_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_model_call_agent ON tenant_template.model_call_logs(tenant_id, agent_name, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_model_call_time ON tenant_template.model_call_logs(tenant_id, created_at DESC);

COMMENT ON TABLE tenant_template.model_call_logs IS '模型调用日志表：每次 LLM 调用的性能与成本追踪';

-- ===== 6. 放松练习记录表 =====
CREATE TABLE IF NOT EXISTS tenant_template.relaxation_sessions (
    relaxation_id   UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id       UUID         NOT NULL,
    student_user_id UUID         NOT NULL REFERENCES tenant_template.users(user_id),
    exercise_type   VARCHAR(64)  NOT NULL DEFAULT 'breathing_323',
    duration_seconds INT         DEFAULT 0,
    completed       BOOLEAN      NOT NULL DEFAULT false,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_relaxation_student ON tenant_template.relaxation_sessions(tenant_id, student_user_id, created_at DESC);

COMMENT ON TABLE tenant_template.relaxation_sessions IS '放松练习记录表：呼吸练习/正念练习完成记录';

-- ===== 7. 教师备注表 =====
CREATE TABLE IF NOT EXISTS tenant_template.teacher_notes (
    note_id         UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id       UUID         NOT NULL,
    student_user_id UUID         NOT NULL REFERENCES tenant_template.users(user_id),
    teacher_user_id UUID         NOT NULL REFERENCES tenant_template.users(user_id),
    content         VARCHAR(2048) NOT NULL,
    note_type       VARCHAR(32)  NOT NULL DEFAULT 'general',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_notes_student ON tenant_template.teacher_notes(tenant_id, student_user_id, created_at DESC);

COMMENT ON TABLE tenant_template.teacher_notes IS '教师备注表：个案管理备注';
