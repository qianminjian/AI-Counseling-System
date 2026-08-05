-- V30 回滚：删除知识库审核工作流字段（KB-102 审核功能整体下架时执行）
-- 注意：
--   1. 审核人/审核时间数据将一并删除（不可逆）
--   2. status 数据变更（active→published）与 DEFAULT 'draft' 不回滚：
--      原始 DEFAULT 值未在 V30 中记录，强行猜测有误伤风险；
--      如需还原请按当时 schema 快照手工确认。
--   3. 回滚后新摄入知识将失去 draft 默认值保护（RAG 检索门禁由代码侧
--      ReviewWorkflowStateMachine 继续兜底，仅在删除列后失效）。

ALTER TABLE tenant_template.knowledge_documents
    DROP COLUMN IF EXISTS grade_band,
    DROP COLUMN IF EXISTS source_type,
    DROP COLUMN IF EXISTS evidence_level,
    DROP COLUMN IF EXISTS reviewer,
    DROP COLUMN IF EXISTS reviewed_at;
