-- ============================================================
-- MindSafe 全量数据库 Schema（PostgreSQL 16 + pgvector）
-- 合并 Flyway V1-V29 最终态 | 生成日期：2026-08-02
-- 用途：新环境一次性建库（替代逐版本迁移），仅供参照/灾备重建
-- 注意：生产环境仍以 Flyway 迁移为准，本脚本不纳入版本管理流程
-- ============================================================

-- ===== 扩展 =====
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ============================================================
-- PUBLIC SCHEMA（全局数据，不按租户隔离）
-- ============================================================

-- 租户表（SaaS 隔离根）
CREATE TABLE IF NOT EXISTS tenants (
    tenant_id       UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_code     VARCHAR(64)  NOT NULL,
    tenant_name     VARCHAR(128) NOT NULL,
    data_region     VARCHAR(32)  DEFAULT 'cn-east',
    kms_key_ref     VARCHAR(128),
    status          VARCHAR(20)  NOT NULL DEFAULT 'active',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    CONSTRAINT uq_tenants_code UNIQUE (tenant_code)
);

CREATE INDEX idx_tenants_status ON tenants(status);

COMMENT ON TABLE tenants IS '租户表：SaaS 隔离根，一个租户对应一个独立 Schema';

-- 学校表
CREATE TABLE IF NOT EXISTS schools (
    school_id       UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id       UUID         NOT NULL REFERENCES tenants(tenant_id),
    school_code     VARCHAR(64)  NOT NULL,
    school_name     VARCHAR(128) NOT NULL,
    edu_stage       VARCHAR(32)  DEFAULT 'primary',
    province        VARCHAR(64),
    city            VARCHAR(64),
    district        VARCHAR(64),
    settings        JSONB        DEFAULT '{}',
    status          VARCHAR(20)  NOT NULL DEFAULT 'active',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    CONSTRAINT uq_schools_tenant_code UNIQUE (tenant_id, school_code)
);

CREATE INDEX idx_schools_tenant_status ON schools(tenant_id, status);

COMMENT ON TABLE schools IS '学校表：一个租户下可有多所学校';

-- ============================================================
-- TENANT_TEMPLATE SCHEMA（租户模板，新租户注册时复制此结构）
-- ============================================================

CREATE SCHEMA IF NOT EXISTS tenant_template;

-- ==================== 身份与权限域 ====================

CREATE TABLE tenant_template.users (
    user_id                     UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id                   UUID         NOT NULL,
    school_id                   UUID,
    user_type                   VARCHAR(20)  NOT NULL,          -- student/teacher/head_teacher/admin/guardian/psych_teacher
    external_subject_id_hash    CHAR(64),
    display_name_enc            BYTEA,
    pseudonym                   VARCHAR(64),
    grade_code                  VARCHAR(32),
    class_code                  VARCHAR(32),
    student_no_hash             CHAR(64),
    mobile_enc                  BYTEA,
    email_enc                   BYTEA,
    gender                      VARCHAR(10),                    -- V11: male/female
    dialect                     VARCHAR(32),                    -- V29: 方言偏好
    password_hash               VARCHAR(128),                   -- V5: BCrypt
    must_change_password        BOOLEAN NOT NULL DEFAULT false, -- V6: 首次改密
    pin_hash                    VARCHAR(100),                   -- V13: PIN码哈希
    pin_set_at                  TIMESTAMPTZ,                    -- V13
    password_changed_at         TIMESTAMPTZ,                    -- V14: 90天过期
    family_code                 VARCHAR(6),                     -- V16: 家庭码
    status                      VARCHAR(20)  NOT NULL DEFAULT 'active',
    last_login_at               TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at                  TIMESTAMPTZ
);

CREATE INDEX idx_users_school_type ON tenant_template.users(tenant_id, school_id, user_type);
CREATE INDEX idx_users_class ON tenant_template.users(tenant_id, school_id, grade_code, class_code);
CREATE INDEX idx_users_tenant_type ON tenant_template.users(tenant_id, user_type, status);
CREATE UNIQUE INDEX idx_users_family_code ON tenant_template.users(tenant_id, family_code) WHERE family_code IS NOT NULL;

COMMENT ON TABLE tenant_template.users IS '用户表：统一账号（student/teacher/head_teacher/admin/guardian/psych_teacher）';
COMMENT ON COLUMN tenant_template.users.gender IS '性别：male/female，用于对话风格与 TTS 音色个性化';
COMMENT ON COLUMN tenant_template.users.dialect IS '方言偏好（cantonese/northeastern/sichuan/henan/shandong/hunan/shaanxi/anhui），NULL=普通话';
COMMENT ON COLUMN tenant_template.users.pin_hash IS 'PIN码BCrypt哈希（4-6位数字）';
COMMENT ON COLUMN tenant_template.users.family_code IS '家庭码（6位字母数字，学生注册时生成，家长绑定凭证）';
COMMENT ON COLUMN tenant_template.users.must_change_password IS '首次登录强制改密标记';
COMMENT ON COLUMN tenant_template.users.password_changed_at IS '最近一次密码修改时间（用于 90 天过期判断）';

-- 角色表
CREATE TABLE tenant_template.roles (
    role_id         UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id       UUID         NOT NULL,
    role_code       VARCHAR(64)  NOT NULL,
    role_name       VARCHAR(128) NOT NULL,
    scope_level     VARCHAR(20)  NOT NULL DEFAULT 'school',
    permission_set  JSONB        DEFAULT '[]',
    is_system       BOOLEAN      NOT NULL DEFAULT false,
    status          VARCHAR(20)  NOT NULL DEFAULT 'active',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_roles_tenant_code UNIQUE (tenant_id, role_code)
);

COMMENT ON TABLE tenant_template.roles IS '角色表：RBAC 角色定义';

-- 用户角色授权表
CREATE TABLE tenant_template.user_roles (
    user_role_id    UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id       UUID         NOT NULL,
    user_id         UUID         NOT NULL REFERENCES tenant_template.users(user_id),
    role_id         UUID         NOT NULL REFERENCES tenant_template.roles(role_id),
    school_id       UUID,
    grade_code      VARCHAR(32),
    class_code      VARCHAR(32),
    effective_from  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    effective_to    TIMESTAMPTZ,
    granted_by      UUID,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_user_roles_user ON tenant_template.user_roles(user_id);
CREATE INDEX idx_user_roles_role ON tenant_template.user_roles(role_id);

COMMENT ON TABLE tenant_template.user_roles IS '用户角色授权表：多对多绑定';

-- ==================== 试用准入域 ====================

-- 邀请码表
CREATE TABLE tenant_template.trial_invite_codes (
    code_id         UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id       UUID         NOT NULL,
    code            VARCHAR(32)  NOT NULL,
    max_uses        INT          NOT NULL DEFAULT 1,
    used_count      INT          NOT NULL DEFAULT 0,
    expires_at      TIMESTAMPTZ,
    status          VARCHAR(20)  NOT NULL DEFAULT 'active',
    created_by      UUID,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    bound_user_id   UUID,                    -- V13: 一人一码
    used_at         TIMESTAMPTZ,             -- V13
    batch_id        VARCHAR(50),             -- V13: 批次号
    generated_by    UUID,                    -- V13: 生成者
    CONSTRAINT uq_invite_code UNIQUE (tenant_id, code)
);

CREATE INDEX idx_invite_code_lookup ON tenant_template.trial_invite_codes(tenant_id, code, status);

COMMENT ON TABLE tenant_template.trial_invite_codes IS '试用邀请码：控制试用准入人群范围';

-- 同意留痕表
CREATE TABLE tenant_template.consent_records (
    consent_id      UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id         UUID         NOT NULL,
    tenant_id       UUID         NOT NULL,
    consent_type    VARCHAR(32)  NOT NULL,
    consent_version VARCHAR(16)  NOT NULL,
    consented_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    ip_hash         VARCHAR(64),
    user_agent      VARCHAR(255)
);

CREATE INDEX idx_consent_user ON tenant_template.consent_records(tenant_id, user_id, consent_type);

COMMENT ON TABLE tenant_template.consent_records IS '告知同意留痕：版本化记录，每次版本升级需重新同意';

-- ==================== 家长域 ====================

-- 家长账号表
CREATE TABLE tenant_template.parent_accounts (
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

CREATE INDEX idx_parent_accounts_phone ON tenant_template.parent_accounts(tenant_id, phone, status);

COMMENT ON TABLE tenant_template.parent_accounts IS '家长账号：手机号+密码登录，通过家庭码绑定学生';

-- 家长-学生关联表
CREATE TABLE tenant_template.parent_student_links (
    link_id         UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id       UUID         NOT NULL,
    parent_id       UUID         NOT NULL REFERENCES tenant_template.parent_accounts(parent_id),
    student_user_id UUID         NOT NULL,
    relation        VARCHAR(20)  NOT NULL DEFAULT 'parent',  -- father/mother/grandparent/other
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_parent_student UNIQUE (parent_id, student_user_id)
);

CREATE INDEX idx_parent_links_student ON tenant_template.parent_student_links(tenant_id, student_user_id);
CREATE INDEX idx_parent_links_parent ON tenant_template.parent_student_links(tenant_id, parent_id);

COMMENT ON TABLE tenant_template.parent_student_links IS '家长-学生关联：一个家长可绑多个孩子，一个孩子可绑多个家长';

-- ==================== 心理互动域 ====================

-- 辅导会话表
CREATE TABLE tenant_template.counseling_sessions (
    session_id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id           UUID         NOT NULL,
    school_id           UUID,
    student_user_id     UUID         NOT NULL REFERENCES tenant_template.users(user_id),
    channel             VARCHAR(32)  NOT NULL DEFAULT 'web',
    interaction_mode    VARCHAR(20)  NOT NULL DEFAULT 'text',
    started_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    ended_at            TIMESTAMPTZ,
    session_status      VARCHAR(20)  NOT NULL DEFAULT 'active',
    risk_level_snapshot SMALLINT     DEFAULT 0,
    transcript_policy   VARCHAR(32)  NOT NULL DEFAULT 'summary_only',
    consent_version     VARCHAR(64),
    state_path          JSONB        DEFAULT NULL,             -- V7: CBT 状态机路径
    satisfaction_rating SMALLINT     DEFAULT NULL,             -- V7: 满意度 1-5
    satisfaction_comment VARCHAR(512) DEFAULT NULL,            -- V7
    turn_count          INT          DEFAULT 0,                -- V8: 对话轮次
    session_summary     TEXT,                                  -- V9: AI 摘要
    prompt_version      VARCHAR(100),                          -- V22: Prompt 版本标识
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_sessions_student_time ON tenant_template.counseling_sessions(tenant_id, student_user_id, started_at DESC);
CREATE INDEX idx_sessions_risk ON tenant_template.counseling_sessions(tenant_id, risk_level_snapshot, started_at DESC);
CREATE INDEX idx_sessions_tenant_student ON tenant_template.counseling_sessions(tenant_id, student_user_id, created_at DESC);
CREATE INDEX idx_sessions_tenant_status ON tenant_template.counseling_sessions(tenant_id, session_status, created_at DESC);

COMMENT ON TABLE tenant_template.counseling_sessions IS '辅导会话表：一次有限回合对话';
COMMENT ON COLUMN tenant_template.counseling_sessions.state_path IS 'CBT 状态机路径（CbtSessionState JSON）';
COMMENT ON COLUMN tenant_template.counseling_sessions.session_summary IS 'AI 生成的会话结构化摘要（JSON）';
COMMENT ON COLUMN tenant_template.counseling_sessions.prompt_version IS '会话使用的 Prompt 版本标识（如 SYS_001:v3:treatment_a）';

-- 消息摘要表
CREATE TABLE tenant_template.message_summaries (
    summary_id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id                   UUID         NOT NULL,
    session_id                  UUID         NOT NULL REFERENCES tenant_template.counseling_sessions(session_id),
    student_user_id             UUID         NOT NULL,
    turn_count                  INT          NOT NULL DEFAULT 0,
    emotion_tags                JSONB        DEFAULT '[]',
    topic_tags                  JSONB        DEFAULT '[]',
    risk_signals                JSONB        DEFAULT '[]',
    student_need_summary_enc    BYTEA,
    ai_intervention_summary_enc BYTEA,
    suggested_next_action       VARCHAR(256),
    content_hash                CHAR(64),
    sender_type                 VARCHAR(20)  DEFAULT 'student',   -- V7
    emotion_label               VARCHAR(64)  DEFAULT NULL,        -- V7
    risk_level                  SMALLINT     DEFAULT 0,           -- V7
    content_summary             VARCHAR(1024) DEFAULT NULL,       -- V7
    cbt_fields                  JSONB        DEFAULT NULL,        -- V7
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_msgsum_session ON tenant_template.message_summaries(session_id);
CREATE INDEX idx_msgsum_student_time ON tenant_template.message_summaries(tenant_id, student_user_id, created_at DESC);
CREATE INDEX idx_message_summaries_session ON tenant_template.message_summaries(session_id);

COMMENT ON TABLE tenant_template.message_summaries IS '消息摘要表：替代完整聊天记录，仅存结构化摘要';
COMMENT ON COLUMN tenant_template.message_summaries.cbt_fields IS 'CBT 结构化字段（emotion_label, auto_thought, balanced_thought 等）';

-- ==================== 情绪日记域 ====================

CREATE TABLE tenant_template.emotion_diaries (
    diary_id        UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id       UUID         NOT NULL,
    student_user_id UUID         NOT NULL REFERENCES tenant_template.users(user_id),
    emotion_label   VARCHAR(32)  NOT NULL,
    intensity       SMALLINT     NOT NULL DEFAULT 3,
    note            VARCHAR(512) DEFAULT NULL,
    diary_date      DATE         NOT NULL DEFAULT CURRENT_DATE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_diary_student_date ON tenant_template.emotion_diaries(tenant_id, student_user_id, diary_date);
CREATE INDEX idx_diary_student_time ON tenant_template.emotion_diaries(tenant_id, student_user_id, diary_date DESC);

COMMENT ON TABLE tenant_template.emotion_diaries IS '情绪日记表：学生每日情绪打卡（每天一条）';

-- ==================== 学生画像域 ====================

CREATE TABLE tenant_template.student_profiles (
    profile_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    user_id             UUID NOT NULL,
    emotion_baseline    JSONB NOT NULL DEFAULT '{}',
    communication_pref  JSONB NOT NULL DEFAULT '{}',
    resilience          JSONB NOT NULL DEFAULT '{}',
    risk_trajectory     JSONB NOT NULL DEFAULT '{}',
    social_graph        JSONB NOT NULL DEFAULT '{}',
    growth_track        JSONB NOT NULL DEFAULT '{}',
    personality_traits  JSONB NOT NULL DEFAULT '{}',    -- V18
    version             INT NOT NULL DEFAULT 1,
    total_sessions      INT NOT NULL DEFAULT 0,
    last_updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_student_profile UNIQUE (tenant_id, user_id)
);

CREATE INDEX idx_profile_user ON tenant_template.student_profiles(tenant_id, user_id);
CREATE INDEX idx_student_profiles_student ON tenant_template.student_profiles(user_id, last_updated_at DESC);

COMMENT ON TABLE tenant_template.student_profiles IS '学生心理画像（结构化统计，不存原始对话）';
COMMENT ON COLUMN tenant_template.student_profiles.emotion_baseline IS '情绪基线：分布/波动度/触发主题';
COMMENT ON COLUMN tenant_template.student_profiles.communication_pref IS '沟通偏好：表达深度/偏好风格/活跃时段';
COMMENT ON COLUMN tenant_template.student_profiles.resilience IS '心理韧性：恢复速度/应对技巧/自我效能';
COMMENT ON COLUMN tenant_template.student_profiles.risk_trajectory IS '风险轨迹：等级分布/趋势/敏感主题';
COMMENT ON COLUMN tenant_template.student_profiles.social_graph IS '社交图谱：关键人物(代号化)/满意度/求助意愿';
COMMENT ON COLUMN tenant_template.student_profiles.growth_track IS '成长轨迹：频率/里程碑/干预有效性';
COMMENT ON COLUMN tenant_template.student_profiles.personality_traits IS '性格特征（LLM 提炼）：introversion/sensitivity/curiosity/dominant_interests';

-- ==================== 风险与个案域 ====================

CREATE TABLE tenant_template.risk_events (
    risk_event_id               UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id                   UUID         NOT NULL,
    school_id                   UUID,
    student_user_id             UUID         NOT NULL REFERENCES tenant_template.users(user_id),
    source_type                 VARCHAR(32)  NOT NULL DEFAULT 'session',
    source_id                   UUID,
    risk_type                   VARCHAR(64)  NOT NULL,
    risk_level                  SMALLINT     NOT NULL,
    trigger_signal_summary_enc  BYTEA,
    detected_by                 VARCHAR(64)  NOT NULL DEFAULT 'agent',
    detected_at                 TIMESTAMPTZ  NOT NULL DEFAULT now(),
    status                      VARCHAR(20)  NOT NULL DEFAULT 'open',
    assigned_user_id            UUID,
    closed_at                   TIMESTAMPTZ,
    resolution_note             TEXT,                    -- V21
    resolved_at                 TIMESTAMPTZ,             -- V21
    follow_up_at                TIMESTAMPTZ,             -- V21
    follow_up_note              TEXT,                    -- V21
    follow_up_done              BOOLEAN NOT NULL DEFAULT false,  -- V21
    outcome                     VARCHAR(30),             -- V21: resolved_improved/resolved_stable/escalated_referral/false_positive
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_risk_open ON tenant_template.risk_events(tenant_id, school_id, status, risk_level DESC, detected_at DESC);
CREATE INDEX idx_risk_student_time ON tenant_template.risk_events(tenant_id, student_user_id, detected_at DESC);
CREATE INDEX idx_risk_events_tenant_status ON tenant_template.risk_events(tenant_id, status, created_at DESC);
CREATE INDEX idx_risk_events_tenant_type ON tenant_template.risk_events(tenant_id, risk_type, created_at DESC);
CREATE INDEX idx_risk_followup ON tenant_template.risk_events(tenant_id, follow_up_at) WHERE follow_up_done = false AND follow_up_at IS NOT NULL;

COMMENT ON TABLE tenant_template.risk_events IS '风险事件表：由会话/测评/人工触发的风险记录';
COMMENT ON COLUMN tenant_template.risk_events.outcome IS '最终评估结果：resolved_improved/resolved_stable/escalated_referral/false_positive';

-- ==================== 通知域 ====================

CREATE TABLE tenant_template.notifications (
    notification_id     UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id           UUID         NOT NULL,
    school_id           UUID,
    recipient_user_id   UUID         NOT NULL,
    recipient_role      VARCHAR(64),
    channel             VARCHAR(32)  NOT NULL DEFAULT 'in_app',
    template_code       VARCHAR(64),
    severity            SMALLINT     NOT NULL DEFAULT 1,
    title               VARCHAR(128) NOT NULL,
    body_summary        VARCHAR(512),
    payload_enc         BYTEA,
    related_type        VARCHAR(64),
    related_id          UUID,
    delivery_status     VARCHAR(20)  NOT NULL DEFAULT 'pending',
    sent_at             TIMESTAMPTZ,
    read_at             TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_notifications_recipient ON tenant_template.notifications(tenant_id, recipient_user_id, delivery_status);
CREATE INDEX idx_notifications_related ON tenant_template.notifications(related_type, related_id);

COMMENT ON TABLE tenant_template.notifications IS '通知表：预警推送、系统消息（内容按角色最小化）';

-- ==================== 审计与模型日志域 ====================

-- 审计日志表
CREATE TABLE tenant_template.audit_logs (
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

CREATE INDEX idx_audit_user_time ON tenant_template.audit_logs(tenant_id, user_id, created_at DESC);
CREATE INDEX idx_audit_resource ON tenant_template.audit_logs(tenant_id, resource_type, resource_id);
CREATE INDEX idx_audit_action ON tenant_template.audit_logs(tenant_id, action, created_at DESC);
CREATE INDEX idx_audit_logs_tenant_time ON tenant_template.audit_logs(tenant_id, created_at DESC);

COMMENT ON TABLE tenant_template.audit_logs IS '审计日志表：记录敏感操作（登录/查看学生档案/导出/配置变更）';

-- 模型调用日志表
CREATE TABLE tenant_template.model_call_logs (
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

CREATE INDEX idx_model_call_session ON tenant_template.model_call_logs(session_id, created_at DESC);
CREATE INDEX idx_model_call_agent ON tenant_template.model_call_logs(tenant_id, agent_name, created_at DESC);
CREATE INDEX idx_model_call_time ON tenant_template.model_call_logs(tenant_id, created_at DESC);

COMMENT ON TABLE tenant_template.model_call_logs IS '模型调用日志表：每次 LLM 调用的性能与成本追踪';

-- ==================== AI 能力域 ====================

-- 对话质量评估表
CREATE TABLE tenant_template.quality_scores (
    score_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    session_id          UUID NOT NULL,
    empathy_score       NUMERIC(4,3),
    cbt_completion      NUMERIC(4,3),
    safety_compliance   NUMERIC(4,3),
    engagement_score    NUMERIC(4,3),
    overall_score       NUMERIC(4,3),
    evaluator           VARCHAR(32) NOT NULL DEFAULT 'llm-judge',
    flagged             BOOLEAN NOT NULL DEFAULT FALSE,
    flag_reason         VARCHAR(256),
    raw_response        TEXT,
    evaluated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_quality_session UNIQUE (tenant_id, session_id)
);

CREATE INDEX idx_quality_tenant ON tenant_template.quality_scores(tenant_id, evaluated_at DESC);
CREATE INDEX idx_quality_flagged ON tenant_template.quality_scores(tenant_id, flagged) WHERE flagged = TRUE;
CREATE INDEX idx_quality_scores_session ON tenant_template.quality_scores(session_id);
CREATE INDEX idx_quality_scores_tenant_time ON tenant_template.quality_scores(tenant_id, evaluated_at DESC);

COMMENT ON TABLE tenant_template.quality_scores IS '对话质量评估（LLM-as-Judge 异步评分）';

-- 长期记忆表
CREATE TABLE tenant_template.long_term_memories (
    memory_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL,
    student_user_id UUID NOT NULL,
    session_id      UUID,
    memory_type     VARCHAR(30) NOT NULL DEFAULT 'key_event',  -- key_event / recurring_theme
    content         TEXT NOT NULL,
    emotion_context VARCHAR(50),
    importance      REAL NOT NULL DEFAULT 0.5,
    recall_count    INTEGER NOT NULL DEFAULT 0,
    last_recalled_at TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ltm_student_importance ON tenant_template.long_term_memories(tenant_id, student_user_id, importance DESC, created_at DESC);
CREATE INDEX idx_ltm_session ON tenant_template.long_term_memories(session_id);
CREATE INDEX idx_long_term_memories_student ON tenant_template.long_term_memories(tenant_id, student_user_id, importance DESC);

COMMENT ON TABLE tenant_template.long_term_memories IS '长期记忆表：跨会话关键事件 + 主题记忆，供 Prompt 回注';

-- Prompt 版本管理表
CREATE TABLE tenant_template.prompt_versions (
    version_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID,                           -- NULL = 全局默认
    template_key    VARCHAR(50) NOT NULL,
    version         INTEGER NOT NULL,
    content         TEXT NOT NULL,
    description     VARCHAR(500),
    ab_group        VARCHAR(20) NOT NULL DEFAULT 'control',
    is_active       BOOLEAN NOT NULL DEFAULT false,
    created_by      UUID,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_prompt_version UNIQUE (tenant_id, template_key, version, ab_group)
);

CREATE INDEX idx_prompt_versions_active ON tenant_template.prompt_versions(template_key, ab_group, is_active) WHERE is_active = true;
CREATE INDEX idx_prompt_versions_tenant ON tenant_template.prompt_versions(tenant_id, template_key);
CREATE INDEX idx_prompt_versions_lookup ON tenant_template.prompt_versions(tenant_id, template_key, is_active, ab_group);

COMMENT ON TABLE tenant_template.prompt_versions IS 'Prompt 版本管理（支持 A/B 测试分组）';

-- ==================== RAG 知识库域 ====================

CREATE TABLE tenant_template.knowledge_documents (
    doc_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID,              -- NULL = 全局知识
    title       VARCHAR(200) NOT NULL,
    category    VARCHAR(50) NOT NULL DEFAULT 'general',
    source      VARCHAR(200),
    content     TEXT NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'active',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_knowledge_documents_tenant ON tenant_template.knowledge_documents(tenant_id, category, status);

COMMENT ON TABLE tenant_template.knowledge_documents IS 'RAG 知识库文档（原始文档元数据）';

CREATE TABLE tenant_template.knowledge_chunks (
    chunk_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    doc_id      UUID NOT NULL REFERENCES tenant_template.knowledge_documents(doc_id) ON DELETE CASCADE,
    tenant_id   UUID,
    chunk_index INTEGER NOT NULL,
    content     TEXT NOT NULL,
    embedding   vector(1536),
    token_count INTEGER,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_knowledge_chunks_embedding ON tenant_template.knowledge_chunks USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
CREATE INDEX idx_knowledge_chunks_doc ON tenant_template.knowledge_chunks(doc_id);

COMMENT ON TABLE tenant_template.knowledge_chunks IS '文档分块 + 向量嵌入（pgvector 1536 维）';

-- ==================== 放松练习与教师备注 ====================

CREATE TABLE tenant_template.relaxation_sessions (
    relaxation_id   UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id       UUID         NOT NULL,
    student_user_id UUID         NOT NULL REFERENCES tenant_template.users(user_id),
    exercise_type   VARCHAR(64)  NOT NULL DEFAULT 'breathing_323',
    duration_seconds INT         DEFAULT 0,
    completed       BOOLEAN      NOT NULL DEFAULT false,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_relaxation_student ON tenant_template.relaxation_sessions(tenant_id, student_user_id, created_at DESC);

COMMENT ON TABLE tenant_template.relaxation_sessions IS '放松练习记录表：呼吸练习/正念练习完成记录';

CREATE TABLE tenant_template.teacher_notes (
    note_id         UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id       UUID         NOT NULL,
    student_user_id UUID         NOT NULL REFERENCES tenant_template.users(user_id),
    teacher_user_id UUID         NOT NULL REFERENCES tenant_template.users(user_id),
    content         VARCHAR(2048) NOT NULL,
    note_type       VARCHAR(32)  NOT NULL DEFAULT 'general',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_notes_student ON tenant_template.teacher_notes(tenant_id, student_user_id, created_at DESC);

COMMENT ON TABLE tenant_template.teacher_notes IS '教师备注表：个案管理备注';

-- ==================== 声纹域 ====================

CREATE TABLE tenant_template.voiceprint_embeddings (
    id              BIGSERIAL PRIMARY KEY,
    user_id         UUID NOT NULL,
    tenant_id       UUID NOT NULL,
    embedding       JSONB NOT NULL,              -- 256-dim float 数组
    sample_index    SMALLINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_vp_user FOREIGN KEY (user_id)
        REFERENCES tenant_template.users(user_id) ON DELETE CASCADE
);

CREATE INDEX idx_vp_embeddings_user ON tenant_template.voiceprint_embeddings(user_id);
CREATE INDEX idx_vp_embeddings_tenant ON tenant_template.voiceprint_embeddings(tenant_id);

COMMENT ON TABLE tenant_template.voiceprint_embeddings IS '声纹特征向量（remote模式）：仅存256-dim embedding，不存音频，PIPL数据最小化';

-- ============================================================
-- END OF SCHEMA
-- ============================================================
