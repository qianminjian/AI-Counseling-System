-- V46: doing/92 R-015 Layer2 输出安全审查 JSON 留痕（TC260 人工抽检依据）
-- reviewJson 为 LLM 输出审查的完整判定 JSON（含 category/evidence/decision 等），
-- 此前仅打日志不落库，人工抽检无法回溯判定依据。
ALTER TABLE tenant_template.risk_events ADD COLUMN review_json JSONB;

COMMENT ON COLUMN tenant_template.risk_events.review_json IS 'Layer2 输出安全审查 JSON（LLM reviewJson 原文，output_review 留痕与 recall 召回共用；TC260 人工抽检依据）';
