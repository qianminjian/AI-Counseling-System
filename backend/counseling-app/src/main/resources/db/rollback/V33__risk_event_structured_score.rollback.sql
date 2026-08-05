-- V33 回滚：删除 risk_events 结构化评分列（RISK-203 可解释评分整体下架时执行）
-- 注意：已有评分数据将一并删除（不可逆），回滚前如需保留请先导出。

ALTER TABLE tenant_template.risk_events
    DROP COLUMN IF EXISTS risk_score,
    DROP COLUMN IF EXISTS reason_codes;
