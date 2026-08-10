-- V40: alert_events 增加推送状态列（2026-08-10 议决）
--
-- 背景：企微推送为附加通道（先入库、再异步推送、失败仅标识）。业务告警
-- （source=alertservice）落库后记录推送结果，管理端告警中心可展示"推送状态"。
-- 语义：
--   PENDING  已入库、推送进行中/待推送
--   SUCCESS  推送成功
--   FAILED   推送失败（企微不可达/超时等，仅标识不影响数据链路）
--   SKIPPED  无推送通道（LoggingAlertService 日志降级通道）
--   NULL     不适用（source=alertmanager 采集记录，推送由 AlertManager 侧负责）
-- 关联：doing/83_服务降级监控与告警设计.md §3.6；AlertEvent.notifyStatus 字段。

ALTER TABLE tenant_template.alert_events ADD COLUMN notify_status VARCHAR(16);

COMMENT ON COLUMN tenant_template.alert_events.notify_status IS
    '推送状态: PENDING/SUCCESS/FAILED/SKIPPED（alertservice 来源）；alertmanager 来源为 NULL（推送由 AlertManager 负责）';
