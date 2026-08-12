-- V48: degradation_events 幂等去重键（专题 F P1-4，联动板块05 P0-4）
--
-- 背景：多实例下降级事件防抖为实例内存态（Redis 锁只防并发扫描、不防冷却窗口），
-- 实例 A 标记 degraded 后，实例 B 在防抖窗口内仍会重复写 auto 事件（告警风暴 +
-- 重复事件污染管理端 M3 时间线）。本迁移为 DB 层幂等兜底提供 dedup_key 唯一键：
--   * auto 事件由检测器生成 dedup_key（格式：trigger:point:from->to:时间桶），
--     同防抖窗口内重复写入走 ON CONFLICT DO NOTHING 静默跳过；
--   * manual 事件（管理端切换 API 写库）不填 dedup_key（NULL），
--     partial unique index 不约束 NULL → 既有写入路径完全兼容。
-- 时间桶 = occurred_at epoch 秒 / 防抖窗口（默认 24h），窗口翻页后同一转换可再落库
-- （长降级周期可多次留痕，符合时间线语义）。
-- 关联：design/doing/audit-report-05 P0-4 / audit-report-06 P1-4 / 汇总报告 §3 专题 F。

ALTER TABLE tenant_template.degradation_events ADD COLUMN dedup_key VARCHAR(128);

COMMENT ON COLUMN tenant_template.degradation_events.dedup_key IS '幂等去重键（auto: trigger:point:from->to:时间桶，防抖窗口内唯一；manual 为 NULL）';

-- 幂等兜底唯一键（partial：仅约束 auto 事件，NULL 不参与唯一性）
CREATE UNIQUE INDEX uq_degradation_events_dedup_key
    ON tenant_template.degradation_events (dedup_key) WHERE dedup_key IS NOT NULL;
