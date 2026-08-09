-- V34 rollback: 运维监控事件表（OPS-MON-007/008）
-- 回滚：删除两张平台级事件表（降级监控链路停用时整体回退）

DROP TABLE IF EXISTS tenant_template.alert_events;
DROP TABLE IF EXISTS tenant_template.degradation_events;
