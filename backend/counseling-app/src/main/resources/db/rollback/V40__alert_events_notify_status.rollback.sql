-- V40 rollback: alert_events 推送状态列（2026-08-10 议决）
-- 回滚：删除 notify_status 列（推送状态展示能力回退，数据丢失由备份恢复兜底）

ALTER TABLE tenant_template.alert_events DROP COLUMN IF EXISTS notify_status;
