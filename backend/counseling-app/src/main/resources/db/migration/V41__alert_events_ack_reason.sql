-- V41: alert_events 增加确认原因列（2026-08-10 code-review H1）
--
-- 背景：告警确认（ack）前端强制 reason 必填（审计留痕），但原实现校验后即丢弃。
-- 补充 ack_reason 列承载确认原因，与 acknowledged_by/acknowledged_at 共同构成
-- 完整审计证据（管理端告警中心可回看确认人/时间/原因）。
-- 关联：doing/83 后台管理端 §7.2（ADMIN-P1-08）；AlertEvent.ackReason 字段。

ALTER TABLE tenant_template.alert_events ADD COLUMN ack_reason VARCHAR(512);

COMMENT ON COLUMN tenant_template.alert_events.ack_reason IS '确认原因（ack 时必填，审计留痕；V41 新增）';
