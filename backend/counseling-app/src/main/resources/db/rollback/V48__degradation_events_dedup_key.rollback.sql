-- V48 rollback: degradation_events 幂等去重键（专题 F P1-4）
-- 回滚：先删 partial unique index（依赖 dedup_key 列），再删 dedup_key 列

DROP INDEX IF EXISTS tenant_template.uq_degradation_events_dedup_key;
ALTER TABLE tenant_template.degradation_events DROP COLUMN IF EXISTS dedup_key;
