-- P0-4: 风险通知 outbox 补偿（RED 通知不允许静默丢失）
-- 为 risk_events 增加通知状态跟踪：pending（已落库未通知）/ sent / failed / dead（超限放弃，转人工兜底）

ALTER TABLE tenant_template.risk_events
  ADD COLUMN IF NOT EXISTS notify_status VARCHAR(20) NOT NULL DEFAULT 'pending',
  ADD COLUMN IF NOT EXISTS notify_attempts SMALLINT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS last_notify_attempt_at TIMESTAMPTZ;

COMMENT ON COLUMN tenant_template.risk_events.notify_status IS '通知状态: pending/sent/failed/dead（outbox 补偿，P0-4）';
COMMENT ON COLUMN tenant_template.risk_events.notify_attempts IS '通知尝试次数（含补偿扫描重试，上限 5 次）';
COMMENT ON COLUMN tenant_template.risk_events.last_notify_attempt_at IS '最后一次通知尝试时间';

-- 补偿扫描索引：待重试事件（failed/pending + 未超限 + 24h 内）
CREATE INDEX IF NOT EXISTS idx_risk_notify_retry
    ON tenant_template.risk_events (notify_status, notify_attempts, detected_at DESC)
    WHERE notify_status IN ('pending', 'failed') AND notify_attempts < 5;
