-- V37: M4 计量采集层（ADMIN-P3-01，doing/83 后台管理端 AdminConsole 设计方案）
--
-- 背景：M4 租户计量——属计量非计费，先行落地采集层（2026-08-09 议决 DEC-007）：
-- usage_events 记录活跃学生快照/LLM 调用等计量事件，供用量报表（计费层
-- rate_plans/subscriptions 等设计冻结待 frozen/38 解冻，本迁移不涉及）。
-- 关联：设计文档 doing/83 §6.6。
-- 幂等设计：UNIQUE(metric, tenant_id, event_time) 支撑采集 upsert 去重（同窗口重复执行不重复计）。

-- ===== usage_events（计量事件，§6.6） =====
CREATE TABLE IF NOT EXISTS tenant_template.usage_events (
    event_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID,                                -- 租户（可空=平台级）
    metric      VARCHAR(32)  NOT NULL,               -- active_student_snapshot/llm_call/tts_call/asr_call
    value       NUMERIC      NOT NULL,               -- 数量（token 数/调用次数/学生数）
    unit        VARCHAR(16)  NOT NULL,               -- count/token/seconds
    event_time  TIMESTAMPTZ  NOT NULL,               -- 事件时间（聚合窗口起点）
    ref_id      UUID                                 -- 关联（session_id/call_id，可空）
);

COMMENT ON TABLE  tenant_template.usage_events IS '计量事件（M4 采集层，计量非计费，DEC-007 先行）';
COMMENT ON COLUMN tenant_template.usage_events.metric IS 'active_student_snapshot=活跃学生快照/llm_call=LLM 调用（token）/tts_call/asr_call';
COMMENT ON COLUMN tenant_template.usage_events.unit IS 'count/token/seconds';

-- 采集去重键（同租户同指标同窗口只记一次；tenant_id 可空用 COALESCE 兼容 NULL）
CREATE UNIQUE INDEX IF NOT EXISTS uq_usage_events_metric_tenant_time
    ON tenant_template.usage_events (metric, COALESCE(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid), event_time);

-- 用量报表查询索引（按指标 + 时间窗口）
CREATE INDEX IF NOT EXISTS idx_usage_events_metric_time
    ON tenant_template.usage_events (metric, event_time DESC);
