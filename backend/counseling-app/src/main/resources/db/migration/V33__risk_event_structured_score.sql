-- V33：risk_events 增加结构化评分列（RISK-203 可解释评分落库，A2 审计修复 2026-08-05）
-- 背景：ConversationRiskProcessor 计算的 ScoreResult（score/reason_codes）原只打日志不落库，
-- 教师端与画像无法消费结构化评分。用户已批准加列方案（红线 #3 已确认）。

ALTER TABLE tenant_template.risk_events ADD COLUMN risk_score SMALLINT;
ALTER TABLE tenant_template.risk_events ADD COLUMN reason_codes JSONB;

COMMENT ON COLUMN tenant_template.risk_events.risk_score IS '结构化风险评分（RISK-203，0-100，供教师端排序/复核/画像）';
COMMENT ON COLUMN tenant_template.risk_events.reason_codes IS '可解释评分项 JSON 数组（如 ["intent_explicit","plan_method"]，供教师复核/审计）';
