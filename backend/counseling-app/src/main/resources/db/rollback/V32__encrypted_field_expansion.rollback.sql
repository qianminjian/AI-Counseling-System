-- V32 回滚：加密字段容量恢复 + 僵尸列清理回退说明
-- 注意：
--   1. content_summary 由 TEXT 缩回 VARCHAR(1024)：若已存在超长密文（>1024 字符）
--      回滚将失败——回滚前必须先用 SQL 清查：
--         SELECT count(*) FROM tenant_template.message_summaries
--         WHERE char_length(content_summary) > 1024;
--      存在超长行时禁止直接回滚，需先完成业务侧数据搬迁。
--   2. 僵尸列（student_need_summary_enc / ai_intervention_summary_enc）已由
--      V32 正式删除，数据不可恢复，回滚不重建（重建空列无意义且误导）。

ALTER TABLE tenant_template.message_summaries
    ALTER COLUMN content_summary TYPE VARCHAR(1024);
