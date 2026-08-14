-- V46 rollback: Layer2 输出安全审查 JSON 留痕列（doing/92 R-015）
-- 回滚：删除 review_json 列（TC260 人工抽检依据回退，抽检改回日志溯源）

ALTER TABLE tenant_template.risk_events DROP COLUMN IF EXISTS review_json;
