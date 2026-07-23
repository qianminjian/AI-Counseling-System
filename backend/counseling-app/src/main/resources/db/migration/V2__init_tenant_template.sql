-- V2: 租户 Schema 模板（M1 核心表）
-- 此 Schema 作为模板，新租户注册时复制此结构到 tenant_{id} Schema

CREATE SCHEMA IF NOT EXISTS tenant_template;

-- ===== 身份与权限域 =====

CREATE TABLE tenant_template.users (
    user_id                     UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id                   UUID         NOT NULL,
    school_id                   UUID,
    user_type                   VARCHAR(20)  NOT NULL,
    external_subject_id_hash    CHAR(64),
    display_name_enc            BYTEA,
    pseudonym                   VARCHAR(64),
    grade_code                  VARCHAR(32),
    class_code                  VARCHAR(32),
    student_no_hash             CHAR(64),
    mobile_enc                  BYTEA,
    email_enc                   BYTEA,
    status                      VARCHAR(20)  NOT NULL DEFAULT 'active',
    last_login_at               TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at                  TIMESTAMPTZ
);

CREATE INDEX idx_users_school_type ON tenant_template.users(tenant_id, school_id, user_type);
CREATE INDEX idx_users_class ON tenant_template.users(tenant_id, school_id, grade_code, class_code);

COMMENT ON TABLE tenant_template.users IS '用户表：统一账号（student/teacher/head_teacher/admin/guardian）';

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

-- ===== 心理互动域 =====

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
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_sessions_student_time ON tenant_template.counseling_sessions(tenant_id, student_user_id, started_at DESC);
CREATE INDEX idx_sessions_risk ON tenant_template.counseling_sessions(tenant_id, risk_level_snapshot, started_at DESC);

COMMENT ON TABLE tenant_template.counseling_sessions IS '辅导会话表：一次有限回合对话';

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
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_msgsum_session ON tenant_template.message_summaries(session_id);
CREATE INDEX idx_msgsum_student_time ON tenant_template.message_summaries(tenant_id, student_user_id, created_at DESC);

COMMENT ON TABLE tenant_template.message_summaries IS '消息摘要表：替代完整聊天记录，仅存结构化摘要';

-- ===== 风险与个案域 =====

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
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_risk_open ON tenant_template.risk_events(tenant_id, school_id, status, risk_level DESC, detected_at DESC);
CREATE INDEX idx_risk_student_time ON tenant_template.risk_events(tenant_id, student_user_id, detected_at DESC);

COMMENT ON TABLE tenant_template.risk_events IS '风险事件表：由会话/测评/人工触发的风险记录';

-- ===== 通知域 =====

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
