-- V31 回滚：删除风险通知 outbox 补偿列（P0-4 通知补偿整体下架时执行）
-- 注意：部分索引 idx_risk_notify_retry 依赖 notify_status/notify_attempts 列，随列删除自动移除；
--       pending/failed 未通知事件将失去补偿机制，回滚前请确认 outbox 已排空或改由人工兜底。

ALTER TABLE tenant_template.risk_events
    DROP COLUMN IF EXISTS notify_status,
    DROP COLUMN IF EXISTS notify_attempts,
    DROP COLUMN IF EXISTS last_notify_attempt_at;
