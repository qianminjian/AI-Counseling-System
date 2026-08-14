-- V41 rollback: alert_events 确认原因列（2026-08-10 code-review H1）
-- 回滚：删除 ack_reason 列（审计留痕回退，ack 记录仅保留 确认人/时间）

ALTER TABLE tenant_template.alert_events DROP COLUMN IF EXISTS ack_reason;
