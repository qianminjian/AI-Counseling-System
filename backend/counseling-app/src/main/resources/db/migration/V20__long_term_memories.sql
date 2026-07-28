-- AI-008: 长期记忆表（跨会话关键事件 + 主题记忆）
-- 存储从每次会话中 LLM 提炼的关键事件/ recurring 主题，供后续会话 Prompt 回注

CREATE TABLE IF NOT EXISTS tenant_template.long_term_memories (
    memory_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL,
    student_user_id UUID NOT NULL,
    session_id    UUID,
    memory_type   VARCHAR(30) NOT NULL DEFAULT 'key_event',
    -- key_event: 单次重大事件（突破/危机/承诺/转折）
    -- recurring_theme: 反复出现的主题（跨多次会话累积）
    content       TEXT NOT NULL,
    emotion_context VARCHAR(50),
    importance    REAL NOT NULL DEFAULT 0.5,
    -- 0.0~1.0，越高越优先回注
    recall_count  INTEGER NOT NULL DEFAULT 0,
    last_recalled_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 按学生+重要性检索（Prompt 回注时 top-N）
CREATE INDEX IF NOT EXISTS idx_ltm_student_importance
    ON tenant_template.long_term_memories (tenant_id, student_user_id, importance DESC, created_at DESC);

-- 按会话去重（同一会话不重复提取）
CREATE INDEX IF NOT EXISTS idx_ltm_session
    ON tenant_template.long_term_memories (session_id);
