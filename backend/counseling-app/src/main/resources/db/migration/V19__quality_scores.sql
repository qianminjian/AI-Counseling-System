-- V19: 对话质量评估表（AI-001 + AI-002）
-- LLM-as-Judge 异步评分结果存储

CREATE TABLE IF NOT EXISTS tenant_template.quality_scores (
    score_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    session_id          UUID NOT NULL,

    -- 四维评分（0.0 ~ 1.0）
    empathy_score       NUMERIC(4,3),
    cbt_completion      NUMERIC(4,3),
    safety_compliance   NUMERIC(4,3),
    engagement_score    NUMERIC(4,3),

    -- 综合分（加权平均）
    overall_score       NUMERIC(4,3),

    -- 评估元数据
    evaluator           VARCHAR(32) NOT NULL DEFAULT 'llm-judge',
    flagged             BOOLEAN NOT NULL DEFAULT FALSE,
    flag_reason         VARCHAR(256),
    raw_response        TEXT,
    evaluated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_quality_session UNIQUE (tenant_id, session_id)
);

CREATE INDEX IF NOT EXISTS idx_quality_tenant
    ON tenant_template.quality_scores(tenant_id, evaluated_at DESC);
CREATE INDEX IF NOT EXISTS idx_quality_flagged
    ON tenant_template.quality_scores(tenant_id, flagged) WHERE flagged = TRUE;

COMMENT ON TABLE tenant_template.quality_scores IS '对话质量评估（LLM-as-Judge 异步评分）';
COMMENT ON COLUMN tenant_template.quality_scores.empathy_score IS '共情度：AI 是否准确识别并回应学生情绪';
COMMENT ON COLUMN tenant_template.quality_scores.cbt_completion IS 'CBT 完成度：是否推进了 CBT 流程（情境→想法→感受→替代想法）';
COMMENT ON COLUMN tenant_template.quality_scores.safety_compliance IS '安全合规：是否遵守危机干预规则、不越界、不诊断';
COMMENT ON COLUMN tenant_template.quality_scores.engagement_score IS '互动投入度：学生参与程度、对话深度、是否有效引导';
