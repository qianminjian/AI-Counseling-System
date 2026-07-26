-- V10: 情绪日记表（学生每日打卡）
CREATE TABLE IF NOT EXISTS tenant_template.emotion_diaries (
    diary_id        UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id       UUID         NOT NULL,
    student_user_id UUID         NOT NULL REFERENCES tenant_template.users(user_id),
    emotion_label   VARCHAR(32)  NOT NULL,
    intensity       SMALLINT     NOT NULL DEFAULT 3,
    note            VARCHAR(512) DEFAULT NULL,
    diary_date      DATE         NOT NULL DEFAULT CURRENT_DATE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_diary_student_date
    ON tenant_template.emotion_diaries(tenant_id, student_user_id, diary_date);

CREATE INDEX IF NOT EXISTS idx_diary_student_time
    ON tenant_template.emotion_diaries(tenant_id, student_user_id, diary_date DESC);

COMMENT ON TABLE tenant_template.emotion_diaries IS '情绪日记表：学生每日情绪打卡（每天一条）';
