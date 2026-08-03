-- V30: 知识库审核字段 + status 统一（KB-102，design/15 / design/49 §五）
-- 1. 补审核工作流字段（grade_band 年级段 / source_type 来源类型 / evidence_level 循证等级 / reviewer 审核人 / reviewed_at 审核时间）
-- 2. status 统一为审核工作流枚举：draft / in_review / published / deprecated
--    历史语料 status='active' 视为已人工审定，迁移为 'published'（ReviewWorkflowStateMachine.fromDbStatus 既定语义）
-- 3. 新摄入默认 'draft'（铁律：仅 published 可被 RAG 检索，新内容须过审）

ALTER TABLE tenant_template.knowledge_documents
    ADD COLUMN IF NOT EXISTS grade_band VARCHAR(20),
    ADD COLUMN IF NOT EXISTS source_type VARCHAR(30),
    ADD COLUMN IF NOT EXISTS evidence_level VARCHAR(20),
    ADD COLUMN IF NOT EXISTS reviewer VARCHAR(100),
    ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMPTZ;

UPDATE tenant_template.knowledge_documents SET status = 'published' WHERE status = 'active';

ALTER TABLE tenant_template.knowledge_documents ALTER COLUMN status SET DEFAULT 'draft';
