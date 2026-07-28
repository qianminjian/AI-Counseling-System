-- DATA-004: 预警追踪闭环（处置→回访→评估）
-- 扩展 risk_events 表，支持完整生命周期

ALTER TABLE tenant_template.risk_events
  ADD COLUMN IF NOT EXISTS resolution_note TEXT,
  ADD COLUMN IF NOT EXISTS resolved_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS follow_up_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS follow_up_note TEXT,
  ADD COLUMN IF NOT EXISTS follow_up_done BOOLEAN NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS outcome VARCHAR(30);
-- outcome: resolved_improved / resolved_stable / escalated_referral / false_positive

COMMENT ON COLUMN tenant_template.risk_events.resolution_note IS '处置记录（教师填写）';
COMMENT ON COLUMN tenant_template.risk_events.follow_up_at IS '计划回访时间';
COMMENT ON COLUMN tenant_template.risk_events.follow_up_note IS '回访记录';
COMMENT ON COLUMN tenant_template.risk_events.outcome IS '最终评估结果';

-- 回访问询索引（查找待回访事件）
CREATE INDEX IF NOT EXISTS idx_risk_followup
    ON tenant_template.risk_events (tenant_id, follow_up_at)
    WHERE follow_up_done = false AND follow_up_at IS NOT NULL;
