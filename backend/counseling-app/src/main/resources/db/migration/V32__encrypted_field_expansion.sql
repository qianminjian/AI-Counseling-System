-- V32: 加密字段容量扩展（AUDIT-P0-3）
--
-- 背景：message_summaries.content_summary 原为 VARCHAR(1024)（V7 定义），
-- 字段级加密（AES-256-GCM + base64，密文膨胀约 1.35 倍）后，
-- 长消息（如 1024 中文字符 ≈ 3072 字节）密文约 4139 字符，
-- 超出 VARCHAR(1024) 上限 → INSERT 报错被业务 catch 吞掉 → 摘要静默丢失。
--
-- 修复：扩为 TEXT（PostgreSQL TEXT 上限 1GB），密文容量充足。
-- 关联：AUDIT-P1-8 session_summary 为 V9 定义的 TEXT，无需扩容，
--       仅需代码侧加密接线（MessageSummaryService + 教师端读取解密）。

ALTER TABLE tenant_template.message_summaries
    ALTER COLUMN content_summary TYPE TEXT;

COMMENT ON COLUMN tenant_template.message_summaries.content_summary
    IS '单条消息内容摘要（R-01 字段级加密密文，TEXT 容纳 AES-GCM 密文膨胀，AUDIT-P0-3 从 VARCHAR(1024) 扩展）';

-- ===== AUDIT-P2-16: 清理僵尸列 =====
-- student_need_summary_enc / ai_intervention_summary_enc 为 V2 遗留设计字段，
-- 全代码库无任何读写点（实体字段已删除），BYTEA 空列占据空间并误导后人。

ALTER TABLE tenant_template.message_summaries
    DROP COLUMN IF EXISTS student_need_summary_enc,
    DROP COLUMN IF EXISTS ai_intervention_summary_enc;
